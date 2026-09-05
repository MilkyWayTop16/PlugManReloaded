package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarScanner {

    public record DiscoveredRef(String sourceId, String ref) {
    }

    private static final int MAX_ENTRY_BYTES = 262144;
    private static final long MAX_SCAN_BYTES = 16L * 1024 * 1024;
    private static final int MAX_REFS = 8;
    private static final int MAX_SPIGOT_ID_LENGTH = 7;

    private static final String[] SKIPPED_PACKAGES = {
            "META-INF/", "org/apache/", "com/google/", "org/slf4j/", "io/netty/", "net/kyori/",
            "org/yaml/", "javax/", "kotlin/", "kotlinx/", "org/intellij/", "org/jetbrains/",
            "com/zaxxer/", "org/bstats/", "org/checkerframework/", "com/mysql/", "org/postgresql/",
            "org/h2/", "org/sqlite/", "com/fasterxml/", "org/objectweb/", "net/md_5/", "com/mojang/",
            "org/spongepowered/", "de/tr7zw/", "com/cryptomorin/", "org/inventivetalent/",
            "org/json/", "com/zaxxer/hikari/", "org/reflections/", "co/aikar/", "net/minecraft/",
            "org/bukkit/", "io/papermc/", "com/destroystokyo/", "org/joml/", "it/unimi/"
    };

    private static final Set<String> IGNORED_REFS = Set.of(
            "spigot:7939", "github:MilkBowl/VaultAPI", "github:PaperMC/Paper", "github:SpigotMC/Spigot",
            "github:Bukkit/Bukkit", "github:PlaceholderAPI/PlaceholderAPI", "github:Mojang/DataFixerUpper"
    );

    private static final String MARKER_SPIGOT_SITE = "spigotmc.org/resources/";
    private static final String MARKER_SPIGET = "spiget.org/v2/resources/";
    private static final String MARKER_SPIGOT_LEGACY = "update.php?resource=";
    private static final String MARKER_GITHUB_API = "api.github.com/repos/";
    private static final String MARKER_GITHUB = "github.com/";
    private static final String[] MARKERS_MODRINTH = {
            "modrinth.com/v2/project/", "modrinth.com/plugin/", "modrinth.com/mod/", "modrinth.com/project/"
    };

    private static final Set<String> GITHUB_NON_OWNERS = Set.of(
            "repos", "sponsors", "orgs", "users", "apps", "features", "about", "login",
            "settings", "marketplace", "topics", "search", "collections", "readme"
    );

    private final Map<String, List<DiscoveredRef>> cache = new ConcurrentHashMap<>();

    public List<DiscoveredRef> scan(@Nullable File jar, String mainClass) {
        if (jar == null || !jar.isFile()) {
            return List.of();
        }

        String key;
        try {
            key = jar.getCanonicalPath() + ":" + jar.length() + ":" + jar.lastModified();
        } catch (Throwable t) {
            Log.debug("jarscanner.canonicalpath-failed", "error", t.getMessage());
            key = jar.getAbsolutePath() + ":" + jar.length() + ":" + jar.lastModified();
        }

        List<DiscoveredRef> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        List<DiscoveredRef> found = read(jar, mainClass);
        cache.put(key, found);
        return found;
    }

    private List<DiscoveredRef> read(File jar, String mainClass) {
        Set<DiscoveredRef> refs = new LinkedHashSet<>();
        long scanned = 0L;
        long start = System.nanoTime();

        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                if (refs.size() >= MAX_REFS || scanned >= MAX_SCAN_BYTES) {
                    break;
                }

                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                boolean ownPom = isOwnMavenPom(name, mainClass);
                if (!ownPom && (!name.endsWith(".class") || isSkippedPackage(name))) {
                    continue;
                }
                long declared = entry.getSize();
                if (declared > MAX_ENTRY_BYTES) {
                    continue;
                }

                byte[] data = readEntry(zip, entry, buffer);
                if (data == null) {
                    continue;
                }
                scanned += data.length;

                if (ownPom) {
                    String text = new String(data, StandardCharsets.UTF_8);
                    if (text.contains("http")) {
                        collect(text, refs);
                    }
                } else {
                    scanClassConstantPool(data, refs);
                }
            }
        } catch (Throwable t) {
            Log.debug("jarscanner.scan-failed", t, "jar", jar.getName());
            return List.of();
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        if (!refs.isEmpty()) {
            Log.debug("jarscanner.refs-found",
                    "jar", jar.getName(),
                    "count", String.valueOf(refs.size()),
                    "ms", String.valueOf(ms),
                    "kb", String.valueOf(scanned / 1024));
        }
        return List.copyOf(refs);
    }

    private static @Nullable byte[] readEntry(ZipFile zip, ZipEntry entry, byte[] buffer) {
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, (int) Math.max(0, entry.getSize())));
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ENTRY_BYTES) {
                    return null;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOwnMavenPom(String entryName, @Nullable String mainClass) {
        if (mainClass == null || mainClass.isBlank()) {
            return false;
        }
        if (!entryName.startsWith("META-INF/maven/") || !entryName.endsWith("/pom.xml")) {
            return false;
        }
        int groupStart = "META-INF/maven/".length();
        int groupEnd = entryName.indexOf('/', groupStart);
        if (groupEnd < 0) {
            return false;
        }
        String groupId = entryName.substring(groupStart, groupEnd);
        return groupId.length() >= 4 && mainClass.startsWith(groupId + ".");
    }

    private static boolean isSkippedPackage(String entryName) {
        for (String prefix : SKIPPED_PACKAGES) {
            if (entryName.startsWith(prefix)) {
                return true;
            }
        }
        return entryName.contains("/nbtapi/") || entryName.contains("/nbt/")
                || entryName.contains("/shadow/nbt") || entryName.contains("/shaded/nbt");
    }

    private static void scanClassConstantPool(byte[] data, Set<DiscoveredRef> refs) {
        if (data.length < 10) {
            return;
        }
        if ((data[0] & 0xFF) != 0xCA || (data[1] & 0xFF) != 0xFE
                || (data[2] & 0xFF) != 0xBA || (data[3] & 0xFF) != 0xBE) {
            return;
        }

        int cpCount = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
        int offset = 10;

        for (int i = 1; i < cpCount && offset < data.length && refs.size() < MAX_REFS; i++) {
            int tag = data[offset++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    if (offset + 2 > data.length) return;
                    int length = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                    offset += 2;
                    if (offset + length > data.length) return;
                    if (containsHttp(data, offset, length)) {
                        String text = new String(data, offset, length, StandardCharsets.UTF_8);
                        collect(text, refs);
                    }
                    offset += length;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    i++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 15 -> offset += 3;
                default -> {
                    return;
                }
            }
        }
    }

    private static boolean containsHttp(byte[] data, int offset, int length) {
        if (length < 4) {
            return false;
        }
        int end = offset + length - 3;
        for (int i = offset; i < end; i++) {
            if (data[i] == 'h' && data[i + 1] == 't' && data[i + 2] == 't' && data[i + 3] == 'p') {
                return true;
            }
        }
        return false;
    }

    private static void collect(String text, Set<DiscoveredRef> refs) {
        collectSpigot(text, MARKER_SPIGOT_SITE, refs);
        collectSpigot(text, MARKER_SPIGET, refs);
        collectSpigot(text, MARKER_SPIGOT_LEGACY, refs);
        collectGithub(text, MARKER_GITHUB_API, refs);
        collectGithub(text, MARKER_GITHUB, refs);
        for (String marker : MARKERS_MODRINTH) {
            collectModrinth(text, marker, refs);
        }
    }

    private static void collectSpigot(String text, String marker, Set<DiscoveredRef> refs) {
        int from = 0;
        while (refs.size() < MAX_REFS) {
            int at = text.indexOf(marker, from);
            if (at < 0) {
                return;
            }
            from = at + marker.length();
            String id = readSpigotId(text, from);
            if (id != null && !IGNORED_REFS.contains("spigot:" + id)) {
                refs.add(new DiscoveredRef("spigot", id));
            }
        }
    }

    private static void collectGithub(String text, String marker, Set<DiscoveredRef> refs) {
        int from = 0;
        while (refs.size() < MAX_REFS) {
            int at = text.indexOf(marker, from);
            if (at < 0) {
                return;
            }
            from = at + marker.length();
            String ref = readOwnerRepo(text, from);
            if (ref != null && !IGNORED_REFS.contains("github:" + ref)) {
                refs.add(new DiscoveredRef("github", ref));
            }
        }
    }

    private static void collectModrinth(String text, String marker, Set<DiscoveredRef> refs) {
        int from = 0;
        while (refs.size() < MAX_REFS) {
            int at = text.indexOf(marker, from);
            if (at < 0) {
                return;
            }
            from = at + marker.length();
            String slug = readSegment(text, from);
            if (slug != null && slug.length() >= 3 && !IGNORED_REFS.contains("modrinth:" + slug)) {
                refs.add(new DiscoveredRef("modrinth", slug));
            }
        }
    }

    private static @Nullable String readSpigotId(String text, int start) {
        String segment = readSegment(text, start);
        if (segment == null) {
            return null;
        }
        int dot = segment.lastIndexOf('.');
        String tail = dot >= 0 ? segment.substring(dot + 1) : segment;
        if (tail.isEmpty() || tail.length() > MAX_SPIGOT_ID_LENGTH) {
            return null;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return null;
            }
        }
        return tail;
    }

    private static @Nullable String readOwnerRepo(String text, int start) {
        String owner = readSegment(text, start);
        if (owner == null || GITHUB_NON_OWNERS.contains(owner.toLowerCase(Locale.ROOT))) {
            return null;
        }
        int next = start + owner.length();
        if (next >= text.length() || text.charAt(next) != '/') {
            return null;
        }
        String repo = readSegment(text, next + 1);
        if (repo == null) {
            return null;
        }
        if (repo.toLowerCase(Locale.ROOT).endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        if (repo.isEmpty() || repo.equals(".") || repo.equals("..")) {
            return null;
        }
        return owner + "/" + repo;
    }

    private static @Nullable String readSegment(String text, int start) {
        int i = start;
        int limit = Math.min(text.length(), start + 80);
        while (i < limit && isRefChar(text.charAt(i))) {
            i++;
        }
        if (i == start) {
            return null;
        }
        String segment = text.substring(start, i);
        while (segment.endsWith(".")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        return segment.isEmpty() ? null : segment;
    }

    private static boolean isRefChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
    }

    public List<DiscoveredRef> orderedForLookup(List<DiscoveredRef> refs) {
        List<DiscoveredRef> ordered = new ArrayList<>(refs.size());
        for (DiscoveredRef ref : refs) {
            if ("modrinth".equals(ref.sourceId())) ordered.add(ref);
        }
        for (DiscoveredRef ref : refs) {
            if ("github".equals(ref.sourceId())) ordered.add(ref);
        }
        for (DiscoveredRef ref : refs) {
            if ("spigot".equals(ref.sourceId())) ordered.add(ref);
        }
        return ordered;
    }

    public void invalidate() {
        cache.clear();
    }
}

