package ru.milkyway.plugmanreloaded.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SourceCatalog {

    private static final String RESOURCE = "/plugin-sources.json";
    private static final String USER_RESOURCE_RU = "/sources-custom.yml";
    private static final String USER_RESOURCE_EN = "/sources-custom-en.yml";
    private static final String USER_FILE_NAME = "sources-custom.yml";

    private static final double MIN_NAME_SIMILARITY = 0.85;

    private static final List<String> OPTION_FIELDS =
            List.of("endpoint", "versionPath", "downloadPath", "url", "loaders", "gameVersions",
                    "pinned", "artifactVersion", "artifactSha256", "artifactSize", "artifactFile",
                    "pendingSourceId", "pendingProjectRef", "pendingPageUrl", "pendingVersion",
                    "pendingSha256", "pendingSize", "pendingFile");

    public record CatalogSource(String sourceId, String ref, String url, Map<String, String> options) {}

    public record CatalogEntry(String pluginName, List<String> aliases, List<CatalogSource> sources) {}

    private final Map<String, List<CatalogEntry>> byMainClass;
    private final Map<String, UserEntry> userEntries;
    private final Set<String> reportedMismatches = ConcurrentHashMap.newKeySet();

    private record UserEntry(String mainClass, List<CatalogSource> sources) {}

    public SourceCatalog(File userCatalogFile) {
        this(userCatalogFile, "ru");
    }

    public SourceCatalog(File userCatalogFile, String language) {
        this.byMainClass = load();
        this.userEntries = loadUserCatalog(userCatalogFile, templateResourceFor(language));
    }

    public static File resolveFile(PlugManReloaded plugin) {
        return plugin != null && plugin.getDataFolder() != null ? new File(plugin.getDataFolder(), USER_FILE_NAME) : new File(USER_FILE_NAME);
    }

    private static String templateResourceFor(String language) {
        return "en".equalsIgnoreCase(language) ? USER_RESOURCE_EN : USER_RESOURCE_RU;
    }

    private static Map<String, List<CatalogEntry>> load() {
        Map<String, List<CatalogEntry>> map = new HashMap<>();

        try (InputStream stream = SourceCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                Log.debug("sourcecatalog.resource-not-found", "resource", RESOURCE);
                return Map.of();
            }

            JsonElement parsed = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonElement.class);
            if (!parsed.isJsonObject()) {
                return Map.of();
            }

            JsonArray plugins = parsed.getAsJsonObject().getAsJsonArray("plugins");
            if (plugins == null) {
                return Map.of();
            }

            for (JsonElement element : plugins) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();

                String main = text(entry, "main");
                if (main == null || main.isBlank()) continue;

                JsonArray sources = entry.getAsJsonArray("sources");
                if (sources == null || sources.size() == 0) continue;

                List<CatalogSource> parsedSources = new ArrayList<>();
                for (JsonElement raw : sources) {
                    if (!raw.isJsonObject()) continue;
                    JsonObject source = raw.getAsJsonObject();
                    String id = text(source, "id");
                    String ref = text(source, "ref");
                    if (id == null || ref == null) continue;
                    Map<String, String> options = new HashMap<>();
                    for (String field : OPTION_FIELDS) {
                        String value = text(source, field);
                        if (value != null) {
                            options.put(field, value);
                        }
                    }
                    parsedSources.add(new CatalogSource(id.toLowerCase(Locale.ROOT), ref,
                            text(source, "url"), Map.copyOf(options)));
                }

                if (!parsedSources.isEmpty()) {
                    List<String> aliases = new ArrayList<>();
                    JsonArray rawAliases = entry.getAsJsonArray("aliases");
                    if (rawAliases != null) {
                        for (JsonElement alias : rawAliases) {
                            if (alias.isJsonPrimitive()) {
                                aliases.add(alias.getAsString());
                            }
                        }
                    }
                    map.computeIfAbsent(key(main), unused -> new ArrayList<>())
                            .add(new CatalogEntry(text(entry, "name"), List.copyOf(aliases), List.copyOf(parsedSources)));
                }
            }

            Log.debug("sourcecatalog.loaded", "count", String.valueOf(map.size()));
            map.replaceAll((unused, entries) -> List.copyOf(entries));
        } catch (Exception t) {
            Log.warn("sourcecatalog.load-failed", t, "error", t.getMessage());
            return Map.of();
        }
        return Map.copyOf(map);
    }

    private static Map<String, UserEntry> loadUserCatalog(@Nullable File file, String templateResource) {
        if (file == null) {
            return Map.of();
        }

        if (!file.isFile() && !createUserCatalog(file, templateResource)) {
            return Map.of();
        }

        Map<String, UserEntry> result = new LinkedHashMap<>();
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection plugins = yaml.getConfigurationSection("plugins");
            if (plugins == null) {
                return Map.of();
            }

            for (String pluginName : plugins.getKeys(false)) {
                ConfigurationSection entry = plugins.getConfigurationSection(pluginName);
                if (entry == null) {
                    Log.warn("sourcecatalog.entry-no-params", "plugin", pluginName);
                    continue;
                }

                List<CatalogSource> sources = readUserSources(pluginName, entry);
                if (sources.isEmpty()) {
                    Log.warn("sourcecatalog.entry-no-valid-source", "plugin", pluginName);
                    continue;
                }

                UserEntry parsed = new UserEntry(entry.getString("main"), List.copyOf(sources));
                result.put(PluginMatcher.normalizeName(pluginName), parsed);
                for (String alias : entry.getStringList("aliases")) {
                    result.putIfAbsent(PluginMatcher.normalizeName(alias), parsed);
                }
            }

            if (!result.isEmpty()) {
                Log.info("sourcecatalog.custom-sources-loaded", "count", String.valueOf(result.size()));
            }
        } catch (Exception t) {
            Log.warn("sourcecatalog.custom-file-read-failed", t);
            return Map.of();
        }
        return Map.copyOf(result);
    }

    private static List<CatalogSource> readUserSources(String pluginName, ConfigurationSection entry) {
        List<CatalogSource> sources = new ArrayList<>();
        List<Map<?, ?>> rawSources = entry.getMapList("sources");

        for (Map<?, ?> raw : rawSources) {
            String id = string(raw, "id");
            String ref = string(raw, "ref");
            if (id == null || id.isBlank()) {
                Log.warn("sourcecatalog.source-missing-id", "plugin", pluginName);
                continue;
            }
            if (ref == null || ref.isBlank()) {
                ref = pluginName;
            }

            Map<String, String> options = new HashMap<>();
            for (String field : OPTION_FIELDS) {
                String value = string(raw, field);
                if (value != null) {
                    options.put(field, value);
                }
            }
            sources.add(new CatalogSource(id.trim().toLowerCase(Locale.ROOT), ref.trim(),
                    string(raw, "url"), Map.copyOf(options)));
        }
        return sources;
    }

    private static boolean createUserCatalog(File file, String templateResource) {
        String resolved = templateResource;
        if (SourceCatalog.class.getResource(resolved) == null && !USER_RESOURCE_RU.equals(resolved)) {
            resolved = USER_RESOURCE_RU;
        }
        try (InputStream stream = SourceCatalog.class.getResourceAsStream(resolved)) {
            if (stream == null) {
                Log.debug("sourcecatalog.template-not-found", "resource", resolved);
                return false;
            }
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.copy(stream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception t) {
            Log.warn("sourcecatalog.custom-file-create-failed", t, "file", file.getName());
            return false;
        }
    }

    public List<CatalogSource> lookup(String mainClass, String pluginName) {
        List<CatalogSource> own = lookupUser(mainClass, pluginName);
        if (own != null) {
            return own;
        }

        if (mainClass != null && !mainClass.isBlank()) {
            List<CatalogEntry> entries = byMainClass.getOrDefault(key(mainClass), List.of());
            if (!entries.isEmpty()) {
                String wanted = PluginMatcher.normalizeName(pluginName);
                for (CatalogEntry entry : entries) {
                    if (nameMatches(wanted, entry.pluginName())) {
                        return entry.sources();
                    }
                    for (String alias : entry.aliases()) {
                        if (nameMatches(wanted, alias)) {
                            return entry.sources();
                        }
                    }
                }

                if (reportedMismatches.add(key(mainClass) + "|" + wanted)) {
                    Log.debug("sourcecatalog.mainclass-mismatch", "mainClass", mainClass, "plugin", pluginName);
                }
                return List.of();
            }
        }

        return lookupByName(pluginName);
    }

    public List<CatalogSource> lookupByName(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return List.of();
        List<CatalogSource> user = lookupUser(null, pluginName);
        if (user != null && !user.isEmpty()) return user;

        String wanted = PluginMatcher.normalizeName(pluginName);
        for (List<CatalogEntry> list : byMainClass.values()) {
            for (CatalogEntry entry : list) {
                if (nameMatches(wanted, entry.pluginName())) {
                    return entry.sources();
                }
                for (String alias : entry.aliases()) {
                    if (nameMatches(wanted, alias)) {
                        return entry.sources();
                    }
                }
            }
        }
        return List.of();
    }

    private @Nullable List<CatalogSource> lookupUser(String mainClass, String pluginName) {
        if (userEntries.isEmpty() || pluginName == null || pluginName.isBlank()) {
            return null;
        }

        UserEntry entry = userEntries.get(PluginMatcher.normalizeName(pluginName));
        if (entry == null) {
            return null;
        }

        String required = entry.mainClass();
        if (required != null && !required.isBlank() && !key(required).equals(key(mainClass == null ? "" : mainClass))) {
            Log.debug("sourcecatalog.custom-mainclass-mismatch", "plugin", pluginName, "required", required, "installed", String.valueOf(mainClass));
            return null;
        }
        return entry.sources();
    }

    private static boolean nameMatches(@Nullable String wantedNormalized, String catalogName) {
        if (wantedNormalized == null || wantedNormalized.isEmpty() || catalogName == null) {
            return false;
        }
        String candidate = PluginMatcher.normalizeName(catalogName);
        if (wantedNormalized.equals(candidate)) {
            return true;
        }
        return PluginMatcher.similarity(wantedNormalized, candidate) >= MIN_NAME_SIMILARITY;
    }

    public @Nullable CatalogSource sourceFor(String mainClass, String pluginName, String sourceId) {
        for (CatalogSource source : lookup(mainClass, pluginName)) {
            if (source.sourceId().equalsIgnoreCase(sourceId)) {
                return source;
            }
        }
        return null;
    }

    public @Nullable CatalogSource pinnedSource(String mainClass, String pluginName) {
        for (CatalogSource source : lookup(mainClass, pluginName)) {
            if (source.options() != null && Boolean.parseBoolean(source.options().get("pinned"))) {
                return source;
            }
        }
        return null;
    }

    public boolean hasUserOverride(@Nullable String mainClass, @Nullable String pluginName) {
        return lookupUser(mainClass, pluginName) != null;
    }

    public boolean hasExplicitSource(@Nullable String mainClass, @Nullable String pluginName) {
        return pinnedSource(mainClass, pluginName) != null || hasUserOverride(mainClass, pluginName);
    }

    public static CatalogSource pinnedInstallation(String sourceId, String ref, String url,
                                                    String artifactVersion, String artifactSha256,
                                                    long artifactSize, String artifactFile) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("pinned", "true");
        options.put("clearPending", "true");
        if (artifactVersion != null && !artifactVersion.isBlank()) {
            options.put("artifactVersion", artifactVersion);
        }
        if (artifactSha256 != null && !artifactSha256.isBlank()) {
            options.put("artifactSha256", artifactSha256.toLowerCase(Locale.ROOT));
        }
        if (artifactSize >= 0) {
            options.put("artifactSize", String.valueOf(artifactSize));
        }
        if (artifactFile != null && !artifactFile.isBlank()) {
            options.put("artifactFile", artifactFile);
        }
        return new CatalogSource(sourceId, ref, url, Map.copyOf(options));
    }

    public static CatalogSource pendingInstallation(String sourceId, String ref, String url,
                                                    String version, String sha256, long size, String file) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("pinned", "true");
        options.put("pendingSourceId", sourceId);
        options.put("pendingProjectRef", ref);
        if (url != null && !url.isBlank()) {
            options.put("pendingPageUrl", url);
        }
        if (version != null && !version.isBlank()) {
            options.put("pendingVersion", version);
        }
        if (sha256 != null && !sha256.isBlank()) {
            options.put("pendingSha256", sha256.toLowerCase(Locale.ROOT));
        }
        if (size >= 0) {
            options.put("pendingSize", String.valueOf(size));
        }
        if (file != null && !file.isBlank()) {
            options.put("pendingFile", file);
        }
        return new CatalogSource(sourceId, ref, url, Map.copyOf(options));
    }

    public static synchronized void reconcilePendingInstallations(@Nullable File userCatalogFile, @Nullable File pluginsDir) {
        if (userCatalogFile == null || pluginsDir == null || !userCatalogFile.isFile() || !pluginsDir.isDirectory()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(userCatalogFile);
            ConfigurationSection plugins = yaml.getConfigurationSection("plugins");
            if (plugins == null) {
                return;
            }
            boolean changed = false;
            for (String pluginName : plugins.getKeys(false)) {
                String base = "plugins." + pluginName + ".sources";
                List<Map<?, ?>> sources = yaml.getMapList(base);
                List<Map<String, Object>> reconciled = new ArrayList<>(sources.size());
                for (Map<?, ?> source : sources) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : source.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            copy.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    if (promotePending(pluginName, pluginsDir, copy)) {
                        changed = true;
                    }
                    reconciled.add(copy);
                }
                if (!sources.isEmpty()) {
                    yaml.set(base, reconciled);
                }
            }
            if (changed) {
                File temporary = new File(userCatalogFile.getParentFile(), userCatalogFile.getName() + ".pending.tmp");
                yaml.save(temporary);
                try {
                    Files.move(temporary.toPath(), userCatalogFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception unsupported) {
                    Files.move(temporary.toPath(), userCatalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            Log.warn("sourcecatalog.pending-reconcile-failed", e, "file", userCatalogFile.getName());
        }
    }

    private static boolean promotePending(String pluginName, File pluginsDir, Map<String, Object> source) throws Exception {
        String pendingFile = string(source, "pendingFile");
        if (pendingFile == null || pendingFile.isBlank()) {
            return false;
        }
        java.nio.file.Path pluginsPath = pluginsDir.toPath().toAbsolutePath().normalize();
        java.nio.file.Path activePath = pluginsPath.resolve(pendingFile).normalize();
        if (!activePath.startsWith(pluginsPath)) {
            return false;
        }
        File active = activePath.toFile();
        if (!active.isFile() || !pluginName.equalsIgnoreCase(JarValidator.readPluginName(active))) {
            return false;
        }
        String expectedHash = string(source, "pendingSha256");
        if (expectedHash != null && !expectedHash.isBlank() && !expectedHash.equalsIgnoreCase(sha256(active.toPath()))) {
            return false;
        }
        String expectedSize = string(source, "pendingSize");
        if (expectedSize != null && !expectedSize.isBlank() && Long.parseLong(expectedSize) != active.length()) {
            return false;
        }
        copyPending(source, "pendingVersion", "artifactVersion");
        copyPending(source, "pendingSha256", "artifactSha256");
        copyPending(source, "pendingSize", "artifactSize");
        copyPending(source, "pendingFile", "artifactFile");
        String sourceId = string(source, "pendingSourceId");
        if (sourceId != null && !sourceId.isBlank()) {
            source.put("id", sourceId);
        }
        String ref = string(source, "pendingProjectRef");
        if (ref != null && !ref.isBlank()) {
            source.put("ref", ref);
        }
        String url = string(source, "pendingPageUrl");
        if (url != null && !url.isBlank()) {
            source.put("url", url);
        }
        source.keySet().removeIf(key -> key.startsWith("pending"));
        return true;
    }

    private static void copyPending(Map<String, Object> source, String pendingKey, String activeKey) {
        Object value = source.get(pendingKey);
        if (value != null) {
            source.put(activeKey, value);
        }
    }

    private static String sha256(java.nio.file.Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private static String key(String mainClass) {
        return mainClass.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String string(Map<?, ?> map, String field) {
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }

    public static synchronized boolean writeUserEntry(File userCatalogFile, String pluginName, String mainClass, CatalogSource newSource) {
        return writeUserEntry(userCatalogFile, pluginName, mainClass, newSource, "ru");
    }

    public static synchronized boolean writeUserEntry(@Nullable File userCatalogFile, String pluginName, String mainClass, CatalogSource newSource, String language) {
        if (userCatalogFile == null || pluginName == null || pluginName.isBlank() || newSource == null) {
            return false;
        }

        File tempFile = new File(userCatalogFile.getParentFile(), userCatalogFile.getName() + ".tmp");
        try {
            if (!userCatalogFile.exists()) {
                File parent = userCatalogFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(userCatalogFile);
            if ((yaml.options().getHeader() == null || yaml.options().getHeader().isEmpty())) {
                String templateResource = "en".equalsIgnoreCase(language) ? "sources-custom-en.yml" : "sources-custom.yml";
                InputStream headerStream = SourceCatalog.class.getClassLoader().getResourceAsStream(templateResource);
                if (headerStream == null) {
                    headerStream = SourceCatalog.class.getClassLoader().getResourceAsStream("sources-custom.yml");
                }
                try (InputStream stream = headerStream) {
                    if (stream != null) {
                        YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                        yaml.options().setHeader(def.options().getHeader());
                        yaml.options().setFooter(def.options().getFooter());
                    }
                } catch (Exception e) {
                    Log.debug("usercatalogwriter.header-read-failed", e);
                }
            }

            String base = "plugins." + pluginName;

            if (mainClass != null && !mainClass.isBlank()) {
                yaml.set(base + ".main", mainClass);
            }

            List<Map<?, ?>> existingRaw = yaml.getMapList(base + ".sources");
            List<Map<String, Object>> combined = new ArrayList<>();

            Map<String, Object> targetMap = new LinkedHashMap<>();
            targetMap.put("id", newSource.sourceId());
            targetMap.put("ref", newSource.ref());
            if (newSource.url() != null && !newSource.url().isBlank()) {
                targetMap.put("url", newSource.url());
            }
            if (newSource.options() != null && !newSource.options().isEmpty()) {
                targetMap.putAll(newSource.options());
            }
            boolean clearPending = Boolean.parseBoolean(String.valueOf(targetMap.remove("clearPending")));

            String targetId = newSource.sourceId().toLowerCase(Locale.ROOT);
            String targetRef = newSource.ref().toLowerCase(Locale.ROOT);
            boolean pinNewSource = newSource.options() != null
                    && Boolean.parseBoolean(newSource.options().get("pinned"));

            for (Map<?, ?> raw : existingRaw) {
                Object rawId = raw.get("id");
                Object rawRef = raw.get("ref");
                String idStr = rawId != null ? String.valueOf(rawId).toLowerCase(Locale.ROOT) : "";
                String refStr = rawRef != null ? String.valueOf(rawRef).toLowerCase(Locale.ROOT) : "";

                if (idStr.equals(targetId) && refStr.equals(targetRef)) {
                    for (Map.Entry<?, ?> entry : raw.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            targetMap.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    continue;
                }

                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                if (pinNewSource) {
                    copy.remove("pinned");
                    copy.remove("artifactVersion");
                    copy.remove("artifactSha256");
                    copy.remove("artifactSize");
                    copy.remove("artifactFile");
                }
                combined.add(copy);
            }

            if (clearPending) {
                targetMap.keySet().removeIf(key -> key.startsWith("pending"));
            }

            combined.add(0, targetMap);

            yaml.set(base + ".sources", combined);

            yaml.save(tempFile);
            try {
                Files.move(tempFile.toPath(), userCatalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception fallback) {
                Log.debug("usercatalogwriter.atomic-move-unsupported", fallback);
                Files.move(tempFile.toPath(), userCatalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            Log.warn("usercatalogwriter.save-failed", t, "plugin", pluginName, "file", userCatalogFile.getName());
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (Exception cleanup) {
                Log.debug("usercatalogwriter.temp-file-delete-failed", cleanup, "file", tempFile.getName());
            }
            return false;
        }
    }
}
