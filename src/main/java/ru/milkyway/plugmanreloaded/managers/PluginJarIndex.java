package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class PluginJarIndex {

    public record JarInfo(File file, String declaredName, String version, String authors) {
        public String preferredName() {
            return declaredName != null ? declaredName : stripExtension(file.getName());
        }
    }

    public record JarDescriptor(String declaredName, String version, String authors, List<String> depend, List<String> softDepend, List<String> provides) {
        public JarDescriptor(String declaredName, String version, String authors) {
            this(declaredName, version, authors, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }

    public record IndexSnapshot(List<JarInfo> entries, Map<String, JarInfo> lookup, Map<String, String> displayNames, long builtAt, long dirModified, int jarCount) {
        public static IndexSnapshot empty() {
            return new IndexSnapshot(Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), 0L, -1L, 0);
        }
    }

    private static final long TTL_MILLIS = 5_000L;
    private static final long CHECK_INTERVAL_MS = 1_000L;

    private final PlugManReloaded plugin;
    private final AtomicReference<IndexSnapshot> snapshot = new AtomicReference<>(IndexSnapshot.empty());
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);
    private volatile long lastCheckTime = 0L;
    private final Set<String> warnedDuplicates = ConcurrentHashMap.newKeySet();

    public PluginJarIndex(PlugManReloaded plugin) {
        this.plugin = plugin;
        scanAndPublish();
    }

    public void invalidate() {
        lastCheckTime = 0L;
        warnedDuplicates.clear();
        requestRefreshIfStale();
    }

    public List<JarInfo> findAll(@Nullable String query) {
        List<JarInfo> matches = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return matches;
        }
        requestRefreshIfStale();
        IndexSnapshot snap = snapshot.get();

        String key = normalize(query);
        for (JarInfo info : snap.entries()) {
            if (normalize(info.file().getName()).equals(key)
                    || normalize(stripExtension(info.file().getName())).equals(key)
                    || (info.declaredName() != null && normalize(info.declaredName()).equals(key))) {
                matches.add(info);
            }
        }
        return matches;
    }

    static boolean isInsidePluginsDir(File pluginsDir, File file) {
        if (pluginsDir == null || file == null) return false;
        try {
            return file.getCanonicalFile().toPath().startsWith(pluginsDir.getCanonicalFile().toPath());
        } catch (IOException e) {
            Log.debug("pluginjarindex.canonical-path-check-failed", e, "file", file.getName());
            return false;
        }
    }

    public @Nullable File find(@Nullable String query) {
        if (query == null || query.isBlank()) return null;
        String key = normalize(query);

        requestRefreshIfStale();
        IndexSnapshot snap = snapshot.get();
        JarInfo hit = snap.lookup().get(key);

        if (hit == null) {
            for (JarInfo info : snap.entries()) {
                if (info.declaredName() != null && info.declaredName().equalsIgnoreCase(query)) {
                    hit = info;
                    break;
                }
                String fn = normalize(stripExtension(info.file().getName()));
                if (isVersionedJarName(fn, key)) {
                    hit = info;
                    break;
                }
            }
        }

        if (hit == null) {
            File pluginsDir = pluginsDir();
            if (pluginsDir != null && pluginsDir.isDirectory()) {

                File direct = new File(pluginsDir, query);
                if (direct.isFile() && isInsidePluginsDir(pluginsDir, direct)) return direct;
                if (!query.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    File directJar = new File(pluginsDir, query + ".jar");
                    if (directJar.isFile() && isInsidePluginsDir(pluginsDir, directJar)) return directJar;
                }
                File[] files = pluginsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (files != null) {
                    for (File f : files) {
                        String fn = f.getName();
                        if (fn.equalsIgnoreCase(query) || stripExtension(fn).equalsIgnoreCase(query) || isVersionedJarName(normalize(stripExtension(fn)), key)) {
                            return f;
                        }
                        JarDescriptor desc = readDescriptor(f);
                        if (desc != null && desc.declaredName() != null && desc.declaredName().equalsIgnoreCase(query)) {
                            return f;
                        }
                    }
                }
            }

            Log.debug("pluginjarindex.query-no-match", "query", query);
            return null;
        }
        return hit.file();
    }

    private static boolean isVersionedJarName(String filenameWithoutExt, String key) {
        if (filenameWithoutExt.equalsIgnoreCase(key)) return true;
        if (filenameWithoutExt.startsWith(key + "-") || filenameWithoutExt.startsWith(key + "_")) {
            String suffix = filenameWithoutExt.substring(key.length() + 1);
            if (suffix.startsWith("v") || suffix.startsWith("v.") || (!suffix.isEmpty() && Character.isDigit(suffix.charAt(0)))) {
                return true;
            }
        }
        return false;
    }

    public List<JarInfo> getEntries() {
        requestRefreshIfStale();
        return snapshot.get().entries();
    }

    public List<String> loadableNames(boolean useJar) {
        requestRefreshIfStale();
        IndexSnapshot snap = snapshot.get();

        List<String> result = new ArrayList<>(snap.entries().size());
        for (JarInfo info : snap.entries()) {
            if (isLoaded(info)) continue;
            if (useJar) {
                result.add(info.file().getName());
            } else {
                String display = snap.displayNames().get(normalize(info.file().getName()));
                result.add(display != null ? display : info.preferredName());
            }
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public List<String> loadableNames() {
        return loadableNames(plugin.getConfigManager().isUseJarFileNames());
    }

    public List<String> allJarNames() {
        requestRefreshIfStale();
        IndexSnapshot snap = snapshot.get();

        List<String> result = new ArrayList<>(snap.entries().size());
        for (JarInfo info : snap.entries()) {
            result.add(info.file().getName());
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public List<JarInfo> getUnloadedJars() {
        requestRefreshIfStale();
        IndexSnapshot snap = snapshot.get();

        List<JarInfo> result = new ArrayList<>();
        for (JarInfo info : snap.entries()) {
            if (!isLoaded(info)) {
                result.add(info);
            }
        }
        return result;
    }

    private boolean isLoaded(JarInfo info) {
        if (info.declaredName() == null) return false;

        if (Bukkit.getPluginManager().getPlugin(info.declaredName()) != null) return true;
        for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            if (loaded.getName().equalsIgnoreCase(info.declaredName())) return true;
        }
        return false;
    }

    static boolean shouldRefresh(long dirModified, int currentJarCount,
                                  long snapshotDirModified, int snapshotJarCount, long snapshotBuiltAt,
                                  long now, long ttlMillis) {
        boolean dirChanged = dirModified != snapshotDirModified || currentJarCount != snapshotJarCount;
        boolean expired = now - snapshotBuiltAt > ttlMillis;
        return dirChanged || expired;
    }

    static boolean shouldCheckDisk(long now, long lastCheckTime, long checkIntervalMillis) {
        return now - lastCheckTime >= checkIntervalMillis;
    }

    private void requestRefreshIfStale() {
        long now = System.currentTimeMillis();
        if (!shouldCheckDisk(now, lastCheckTime, CHECK_INTERVAL_MS)) {
            return;
        }
        lastCheckTime = now;

        File pluginsDir = pluginsDir();
        if (pluginsDir == null) return;

        IndexSnapshot current = snapshot.get();
        long dirModified = pluginsDir.lastModified();

        String[] jarFiles = pluginsDir.list((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        int currentJarCount = jarFiles != null ? jarFiles.length : 0;

        boolean needsRefresh = shouldRefresh(dirModified, currentJarCount,
                current.dirModified(), current.jarCount(), current.builtAt(), now, TTL_MILLIS);

        if (needsRefresh && rebuilding.compareAndSet(false, true)) {
            TaskScheduler.runAsync(plugin, () -> {
                try {
                    scanAndPublish();
                } finally {
                    rebuilding.set(false);
                }
            });
        }
    }

    private void scanAndPublish() {
        File pluginsDir = pluginsDir();
        ScanResult result = pluginsDir != null && pluginsDir.isDirectory()
                ? scan(pluginsDir)
                : null;

        if (result != null) {
            snapshot.set(new IndexSnapshot(
                    result.entries(),
                    result.lookup(),
                    result.displayNames(),
                    System.currentTimeMillis(),
                    pluginsDir.lastModified(),
                    result.entries().size()
            ));
            Log.debug("pluginjarindex.indexed", "count", String.valueOf(result.entries().size()));
        } else {
            snapshot.set(IndexSnapshot.empty());
        }
    }

    private record ScanResult(List<JarInfo> entries, Map<String, JarInfo> lookup, Map<String, String> displayNames) {}

    private ScanResult scan(File pluginsDir) {
        File[] files = pluginsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) files = new File[0];

        List<JarInfo> scanned = new ArrayList<>(files.length);
        Map<String, JarInfo> byKey = new HashMap<>();

        for (File file : files) {
            JarDescriptor desc = readDescriptor(file);
            JarInfo info = new JarInfo(file, desc != null ? desc.declaredName() : null,
                    desc != null ? desc.version() : "1.0",
                    desc != null ? desc.authors() : "");
            scanned.add(info);

            String fileName = file.getName();

            byKey.put(normalize(fileName), info);
            byKey.putIfAbsent(normalize(stripExtension(fileName)), info);
            if (info.declaredName() != null) {
                JarInfo previous = byKey.putIfAbsent(normalize(info.declaredName()), info);
                if (previous != null && previous != info) {
                    String pair = normalize(info.declaredName()) + "|"
                            + normalize(previous.file().getName()) + "|" + normalize(fileName);
                    boolean firstTime;
                    synchronized (warnedDuplicates) {
                        firstTime = warnedDuplicates.add(pair);
                    }
                    if (firstTime) {
                        Log.warn("pluginjarindex.duplicate-jar", "plugin", info.declaredName(), "first", previous.file().getName(), "second", fileName);
                    }
                }
            }
        }

        return new ScanResult(
                Collections.unmodifiableList(scanned),
                Collections.unmodifiableMap(byKey),
                buildDisplayNames(scanned)
        );
    }

    private Map<String, String> buildDisplayNames(List<JarInfo> scanned) {
        Map<String, Integer> nameCounts = new HashMap<>();
        for (JarInfo info : scanned) {
            nameCounts.merge(normalize(info.preferredName()), 1, Integer::sum);
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (JarInfo info : scanned) {
            String preferred = info.preferredName();
            boolean ambiguous = nameCounts.getOrDefault(normalize(preferred), 0) > 1;
            result.put(normalize(info.file().getName()), ambiguous ? stripExtension(info.file().getName()) : preferred);
        }
        return Collections.unmodifiableMap(result);
    }

    public static @Nullable JarDescriptor readDescriptor(File file) {
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) entry = jar.getJarEntry("paper-plugin.yml");
            if (entry == null) {
                Log.debug("pluginjarindex.no-descriptor", "file", file.getName());
                return null;
            }

            try (InputStream is = jar.getInputStream(entry)) {
                String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                YamlConfiguration yaml = JarValidator.loadSafeYaml(raw);
                String name = yaml.getString("name");
                if (name == null || name.isBlank()) {
                    name = JarValidator.extractRawField(raw, "name");
                }
                String versionRaw = yaml.getString("version");
                if (versionRaw == null || versionRaw.isBlank()) {
                    versionRaw = JarValidator.extractRawField(raw, "version");
                }
                String version = PluginMetaHelper.cleanVersion(versionRaw != null ? versionRaw : "1.0");
                List<String> authorsList = yaml.getStringList("authors");
                String authors;
                if (!authorsList.isEmpty()) {
                    authors = String.join(", ", authorsList);
                } else {
                    String singleAuthor = yaml.getString("author");
                    if (singleAuthor == null || singleAuthor.isBlank()) {
                        singleAuthor = JarValidator.extractRawField(raw, "author");
                    }
                    authors = (singleAuthor != null && !singleAuthor.isBlank()) ? singleAuthor : "";
                }

                List<String> depend = yaml.getStringList("depend");
                if (depend.isEmpty() && yaml.isConfigurationSection("dependencies.server")) {
                    depend = new ArrayList<>(yaml.getConfigurationSection("dependencies.server").getKeys(false));
                }
                List<String> softDepend = yaml.getStringList("softdepend");
                if (softDepend.isEmpty()) {
                    softDepend = yaml.getStringList("soft-depend");
                }
                List<String> provides = yaml.getStringList("provides");

                return new JarDescriptor(name != null && !name.isBlank() ? name.trim() : null, version, authors, depend, softDepend, provides);
            }
        } catch (Throwable t) {
            Log.debug("pluginjarindex.descriptor-read-failed", "file", file.getName(), "error", t.getMessage());
            return null;
        }
    }

    private @Nullable File pluginsDir() {
        if (plugin == null || plugin.getDataFolder() == null) return null;
        return plugin.getDataFolder().getParentFile();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}

