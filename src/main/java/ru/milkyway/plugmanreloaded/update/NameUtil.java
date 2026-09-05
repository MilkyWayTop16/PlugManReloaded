package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class NameUtil {

    private NameUtil() {}

    private static final int DISTINCTIVE_NAME_MIN_LENGTH = 3;

    private static final Set<String> GENERIC_PACKAGE_SEGMENTS = Set.of(
            "minecraft", "spigotmc", "bukkit", "paper", "google", "apache", "java", "javax", "org", "net", "io", "dev"
    );

    private static final Set<String> GENERIC_NAME_TOKENS = Set.of(
            "clans", "clan", "traps", "trap", "core", "shop", "shops", "chat", "auth", "login", "bank", "home",
            "homes", "warp", "warps", "kit", "kits", "cases", "case", "events", "event", "economy", "auction",
            "market", "seller", "buyer", "region", "regions", "world", "worlds", "player", "players", "admin",
            "protect", "protection", "plugin", "plugins", "mod", "addon", "addons", "lite", "plus", "pro",
            "vault", "menu", "menus", "gui", "scoreboard", "tablist", "spawn", "teleport", "tpa", "pvp", "pve",
            "quests", "quest", "jobs", "job", "money", "coins", "coin"
    );

    private static final Set<String> WEAK_BRAND_TOKENS = Set.of(
            "many", "new", "old", "the", "dev", "app", "net", "com", "www", "api", "mc", "pvp", "fun", "sun"
    );

    static final Set<String> COMPANION_SUFFIXES = Set.of(
            "api", "lib", "library", "addon", "addons", "expansion", "expansions",
            "extension", "bridge", "hook", "hooks", "placeholders", "compat", "gui", "prefixes",
            "sources", "javadoc", "dev", "spawn", "chat", "discord",
            "protect", "geoip", "antibuild", "xmpp", "discordlink"
    );

    private static final Set<String> COMPANION_MARKERS = Set.of(
            "config", "configs", "configuration", "configurations", "preset", "presets",
            "setup", "schematic", "schematics", "translation", "translations", "pack", "packs",
            "tutorial", "guide", "wiki"
    );

    private static final Map<String, List<String>> KNOWN_ALIASES = Map.of(
            "fawe", List.of("fastasyncworldedit"),
            "worldguardtranslator", List.of("worldguard-translator"),
            "quickshophikari", List.of("quickshop-hikari"),
            "citizens", List.of("citizens2"),
            "aureliumskills", List.of("auraskills")
    );

    public static List<String> getSearchAliases(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return List.of();

        List<String> result = new ArrayList<>();
        result.add(pluginName);

        String normalized = normalizeName(pluginName);
        if (normalized.startsWith("essentials") && !normalized.startsWith("essentialsx")) {
            String sub = normalized.substring("essentials".length());
            String xVariant = "essentialsx" + sub;
            if (!result.contains(xVariant)) {
                result.add(xVariant);
            }
            if (!result.contains("essentialsx")) {
                result.add("essentialsx");
            }
        }

        List<String> known = KNOWN_ALIASES.get(normalized);
        if (known != null) {
            for (String alias : known) {
                if (!result.contains(alias)) {
                    result.add(alias);
                }
            }
        }

        String spaced = splitCamelCase(pluginName);
        if (spaced != null && !result.contains(spaced)) {
            result.add(spaced);
        }
        return List.copyOf(result);
    }

    private static @Nullable String splitCamelCase(String name) {
        StringBuilder builder = new StringBuilder(name.length() + 4);
        boolean inserted = false;
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (i > 0 && Character.isUpperCase(current)) {
                char previous = name.charAt(i - 1);
                if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
                    builder.append(' ');
                    inserted = true;
                }
            }
            builder.append(current);
        }
        if (!inserted) {
            return null;
        }
        String spaced = builder.toString().trim();
        return spaced.equals(name) ? null : spaced;
    }

    public static String normalizeName(@Nullable String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[-_.\\s]+v?\\d+(\\.\\d+)*$", "")
                .replaceAll("\\s*v\\d+(\\.\\d+)*$", "")
                .replaceAll("[-_.\\s]+", "")
                .replaceAll("(?:premium|free|lite|pro|ultimate|advanced|spigot|paper|mc|plugin|forge|fabric|dev|beta)$", "")
                .trim();
    }

    public static boolean isCompanion(String pluginName, String candidateName) {
        String plugin = normalizeName(pluginName);
        String candidate = normalizeName(candidateName);
        if (plugin.isEmpty() || candidate.isEmpty() || plugin.equals(candidate)) {
            return false;
        }
        if (candidate.startsWith(plugin)) {
            String tail = candidate.substring(plugin.length());
            if (COMPANION_SUFFIXES.contains(tail)) {
                return true;
            }
        }
        return hasCompanionMarker(plugin, candidateName);
    }

    private static boolean hasCompanionMarker(String normalizedPlugin, String candidateName) {
        for (String token : tokenize(candidateName)) {
            if (COMPANION_MARKERS.contains(token) && !normalizedPlugin.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> tokenize(@Nullable String value) {
        Set<String> tokens = new HashSet<>();
        if (value == null || value.isEmpty()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char symbol = Character.toLowerCase(value.charAt(i));
            if (Character.isLetterOrDigit(symbol)) {
                current.append(symbol);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    public static String primaryResourceName(@Nullable String title) {
        if (title == null) return "";
        String value = title.trim();

        boolean stripped = true;
        while (stripped && !value.isEmpty()) {
            stripped = false;
            char open = value.charAt(0);
            char close = open == '[' ? ']' : open == '(' ? ')' : open == '{' ? '}' : 0;
            if (close != 0) {
                int end = value.indexOf(close);
                if (end > 0) {
                    value = value.substring(end + 1).trim();
                    stripped = true;
                }
            }
        }

        value = value.replace(" - ", "|").replace(" – ", "|").replace(" — ", "|")
                .replace(" » ", "|").replace(" • ", "|").replace(" // ", "|").replace(" | ", "|");

        int i = 0;
        int length = value.length();
        while (i < length) {
            while (i < length && !Character.isLetterOrDigit(value.charAt(i)) && value.charAt(i) != '|') {
                i++;
            }
            if (i >= length) break;

            int end = i;
            while (end < length && isNamePart(value.charAt(end)) && value.charAt(end) != '|') {
                end++;
            }
            String run = stripDecoration(value.substring(i, end)).replaceAll("\\s+", " ");
            if (!run.isEmpty()) {
                if (run.replaceAll("[0-9.\\-–—\\s+vVxX]", "").isEmpty()) {
                    i = end + 1;
                    continue;
                }
                return run;
            }
            i = end + 1;
        }
        return "";
    }

    public static String cleanResourceTitle(@Nullable String title) {
        if (title == null || title.isBlank()) return "";
        String primary = primaryResourceName(title);
        if (!primary.isBlank() && primary.length() >= 2) {
            return primary;
        }
        return title.trim();
    }

    public static double resourceNameSimilarity(String pluginName, String resourceTitle) {
        String plugin = normalizeName(pluginName);
        if (plugin.isEmpty() || resourceTitle == null || resourceTitle.isBlank()) {
            return 0.0;
        }
        double full = similarity(plugin, normalizeName(resourceTitle));
        double primary = similarity(plugin, normalizeName(primaryResourceName(resourceTitle)));
        return Math.max(full, primary);
    }

    public static boolean isDistinctive(String normalizedName) {
        return normalizedName != null && normalizedName.length() >= DISTINCTIVE_NAME_MIN_LENGTH;
    }

    public static boolean isGeneric(String pluginName) {
        String normalized = normalizeName(pluginName);
        if (normalized.isEmpty()) return true;
        if (GENERIC_NAME_TOKENS.contains(normalized)) return true;
        if (normalized.startsWith("anti") && (normalized.contains("lag") || normalized.contains("cheat"))) return true;
        return normalized.contains("clear") && normalized.contains("lag");
    }

    public static boolean authorsMatch(@Nullable List<String> pluginAuthors, String resourceAuthor) {
        if (pluginAuthors == null || pluginAuthors.isEmpty()
                || resourceAuthor == null || resourceAuthor.isBlank()) {
            return false;
        }
        String resource = resourceAuthor.toLowerCase(Locale.ROOT).trim();

        for (String raw : pluginAuthors) {
            if (raw == null) continue;
            for (String part : raw.split("[,;/&]")) {
                String candidate = part.toLowerCase(Locale.ROOT).replaceAll("[\\[\\]\"]", "").trim();
                if (candidate.length() < 3) continue;
                if (resource.contains(candidate) || candidate.contains(resource)) {
                    return true;
                }
                for (String sub : candidate.split("[_\\-\\s]+")) {
                    if (sub.length() >= 4 && resource.contains(sub)) {
                        return true;
                    }
                }
                for (String sub : resource.split("[_\\-\\s]+")) {
                    if (sub.length() >= 4 && candidate.contains(sub)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasBrandCorroboration(PluginIdentity identity, String... haystackParts) {
        Set<String> brands = extractBrandTokens(identity);
        brands.remove(normalizeName(identity.pluginName()));
        brands.removeIf(token -> GENERIC_NAME_TOKENS.contains(token) || WEAK_BRAND_TOKENS.contains(token));
        if (brands.isEmpty()) {
            return false;
        }

        StringBuilder haystack = new StringBuilder();
        for (String part : haystackParts) {
            if (part != null) {
                haystack.append(part.toLowerCase(Locale.ROOT)).append(' ');
            }
        }
        String text = haystack.toString();

        for (String brand : brands) {
            if (appearsAsDistinctToken(brand, text)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> extractBrandTokens(PluginIdentity identity) {
        LinkedHashSet<String> brands = new LinkedHashSet<>();

        if (identity.authors() != null) {
            for (String author : identity.authors()) {
                if (author == null) continue;
                for (String part : author.split("[,/&]+")) {
                    String token = normalizeBrandToken(part);
                    if (token != null) brands.add(token);
                }
            }
        }

        String packageOwner = packageOwnerFromMain(identity.mainClass());
        if (packageOwner != null) {
            brands.add(packageOwner);
        }

        String website = identity.website();
        if (website != null && !website.isBlank() && !"null".equalsIgnoreCase(website)) {
            String lower = website.toLowerCase(Locale.ROOT);
            int schemeEnd = lower.indexOf("://");
            int slash = lower.indexOf('/', schemeEnd >= 0 ? schemeEnd + 3 : 0);
            String hostPath = slash >= 0 ? lower.substring(slash + 1) : "";
            for (String segment : hostPath.split("[/]+")) {
                String token = normalizeBrandToken(segment);
                if (token != null) brands.add(token);
            }
        }

        brands.removeIf(token -> GENERIC_PACKAGE_SEGMENTS.contains(token) || token.length() < 4);
        return brands;
    }

    public static @Nullable String packageOwnerFromMain(@Nullable String mainClass) {
        if (mainClass == null || mainClass.isBlank() || "null".equalsIgnoreCase(mainClass)) {
            return null;
        }
        String[] parts = mainClass.split("\\.");
        if (parts.length >= 3 && "github".equalsIgnoreCase(parts[1])) {
            return parts[2].toLowerCase(Locale.ROOT);
        }
        if (parts.length >= 3) {
            String candidate = parts[1].toLowerCase(Locale.ROOT);
            if (!GENERIC_PACKAGE_SEGMENTS.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static double similarity(@Nullable String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return 0.0;
        if (left.equals(right)) return 1.0;

        double levenshtein = 1.0 - (double) distance(left, right) / Math.max(left.length(), right.length());
        return Math.max(levenshtein, jaccard(left, right));
    }

    private static double jaccard(String left, String right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];

        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static boolean appearsAsDistinctToken(@Nullable String brand, String haystack) {
        if (brand == null || brand.isBlank() || haystack == null || haystack.isBlank()) {
            return false;
        }
        int index = haystack.indexOf(brand);
        while (index >= 0) {
            boolean leftOk = index == 0 || !Character.isLetterOrDigit(haystack.charAt(index - 1));
            int end = index + brand.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            index = haystack.indexOf(brand, index + 1);
        }
        return false;
    }

    private static @Nullable String normalizeBrandToken(@Nullable String raw) {
        if (raw == null) return null;
        String token = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("^@+", "")
                .replaceAll("[^a-z0-9]+", "");
        if (token.length() < 3 || token.length() > 24) {
            return null;
        }
        return token;
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '\'' || c == '_' || c == '+';
    }

    private static String stripDecoration(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && !Character.isLetterOrDigit(value.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end).trim();
    }

    public static boolean isAuthorCompatible(@Nullable List<String> installedAuthors, String remoteAuthor) {
        if (installedAuthors == null || installedAuthors.isEmpty() || remoteAuthor == null || remoteAuthor.isBlank()) {
            return true;
        }
        String remoteClean = normalizeAuthor(remoteAuthor);
        if (remoteClean.isEmpty()) return true;

        for (String installed : installedAuthors) {
            String installedClean = normalizeAuthor(installed);
            if (installedClean.isEmpty()) continue;
            if (installedClean.equals(remoteClean) || remoteClean.contains(installedClean) || installedClean.contains(remoteClean)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeAuthor(@Nullable String author) {
        if (author == null) return "";
        return author.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").trim();
    }
}

