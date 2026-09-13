package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginEdition;
import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginIdentity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PluginMatcher {

    private static final Pattern EDITION_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:^|[._\\-\\[\\(])(prem|premium|paid|pro|plus|elite|ultimate|full)(?:[._\\-\\]\\)\\d]|\\.jar$|$)"
    );

    private static final Pattern STRICT_PREMIUM_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:^|[._\\-\\[\\(])(prem|premium|paid)(?:[._\\-\\]\\)\\d]|\\.jar$|$)"
    );

    private static final Pattern VERSION_SUFFIX_PATTERN = Pattern.compile("[-_.\\s]+v?\\d[a-z0-9.+_-]*$");
    private static final Pattern JAR_SUFFIX_PATTERN = Pattern.compile("\\.jar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATORS_PATTERN = Pattern.compile("[-_.\\s]+");
    private static final Pattern NAME_VERSION_PATTERN_1 = Pattern.compile("[-_.\\s]+v?\\d+(\\.\\d+)*$");
    private static final Pattern NAME_VERSION_PATTERN_2 = Pattern.compile("\\s*v\\d+(\\.\\d+)*$");
    private static final Pattern NAME_EDITION_SUFFIX_PATTERN = Pattern.compile("(?:premium|free|lite|pro|ultimate|advanced|spigot|paper|mc|plugin|forge|fabric|dev|beta)$");
    private static final Pattern SPLIT_NON_RUNTIME_PATTERN = Pattern.compile("[-_.]");
    private static final Pattern AUTHOR_SPLIT_PATTERN = Pattern.compile("[,;/&]");
    private static final Pattern AUTHOR_BRACKETS_PATTERN = Pattern.compile("[\\[\\]\"]");
    private static final Pattern AUTHOR_DELIMITERS_PATTERN = Pattern.compile("[_\\-\\s]+");
    private static final Pattern BRAND_SPLIT_PATTERN = Pattern.compile("[,/&]+");
    private static final Pattern SLASH_SPLIT_PATTERN = Pattern.compile("/+");
    private static final Pattern BRAND_AT_PATTERN = Pattern.compile("^@+");
    private static final Pattern NON_ALPHANUMERIC_MULTI_PATTERN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern NON_ALPHANUMERIC_SINGLE_PATTERN = Pattern.compile("[^a-z0-9]");
    private static final Pattern TITLE_DELIMITERS_PATTERN = Pattern.compile(" [-–—»•|] | // ");
    private static final Pattern WHITESPACE_RUN_PATTERN = Pattern.compile("\\s+");
    private static final Pattern TITLE_FILTER_CHARS_PATTERN = Pattern.compile("[0-9.\\-–—\\s+vVxX]");

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static final Set<String> NON_RUNTIME_CLASSIFIERS = Set.of(
            "javadoc", "sources", "source", "plain", "original", "tests", "test", "shaded-sources", "cli"
    );

    private static final Set<String> FOREIGN_PLATFORMS = Set.of(
            "bungee", "bungeecord", "velocity", "waterfall", "fabric", "forge", "neoforge", "quilt", "sponge", "nukkit"
    );

    private static final List<String> OWN_PLATFORM_SUFFIXES = List.of(
            "-paper", "_paper", ".paper",
            "-purpur", "_purpur", ".purpur",
            "-spigot", "_spigot", ".spigot",
            "-bukkit", "_bukkit", ".bukkit",
            "-folia", "_folia", ".folia"
    );

    private PluginMatcher() {}

    public static PluginEdition detect(File jar, String pluginName, String version, String mainClass, String website) {
        return isPremium(jar, pluginName, version, mainClass, website) ? PluginEdition.PREMIUM : PluginEdition.FREE;
    }

    public static PluginEdition detectEdition(File jar, String pluginName, String version, String mainClass, String website) {
        return detect(jar, pluginName, version, mainClass, website);
    }

    public static double similarity(@Nullable String left, String right) {
        if (left == null || right == null) return 0.0;
        if (endsWithJarIgnoreCase(left) || endsWithJarIgnoreCase(right)) {
            return assetSimilarity(left, right);
        }
        return rawSimilarity(left, right);
    }

    private static boolean isPremium(File jar, String pluginName, String version, String mainClass, String website) {
        if (pluginName != null && STRICT_PREMIUM_TOKEN_PATTERN.matcher(pluginName).find()) {
            return true;
        }

        if (version != null && STRICT_PREMIUM_TOKEN_PATTERN.matcher(version).find()) {
            return true;
        }

        if (website != null && !website.isBlank()) {
            String lowerWeb = website.toLowerCase(Locale.ROOT);
            if (lowerWeb.contains("polymart.org") || lowerWeb.contains("builtbybit.com") || lowerWeb.contains("songoda.com/marketplace")) {
                return true;
            }
        }

        if (jar != null) {
            String jarName = jar.getName();
            if (endsWithJarIgnoreCase(jarName)) {
                jarName = jarName.substring(0, jarName.length() - 4);
            }

            if (pluginName != null && !pluginName.isBlank()) {
                String remainder = stripPrefixIgnoreCase(jarName, pluginName);
                if (remainder.length() < jarName.length()) {
                    if (EDITION_TOKEN_PATTERN.matcher(remainder).find()) {
                        return true;
                    }
                }
            }

            if (STRICT_PREMIUM_TOKEN_PATTERN.matcher(jarName).find()) {
                if (pluginName == null || !normalizeName(jarName).equals(normalizeName(pluginName))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean endsWithJarIgnoreCase(@Nullable String value) {
        if (value == null) return false;
        int len = value.length();
        return len >= 4 && value.regionMatches(true, len - 4, ".jar", 0, 4);
    }

    private static String stripPrefixIgnoreCase(@Nullable String text, String prefix) {
        if (text == null || prefix == null) return text;
        if (text.length() >= prefix.length() && text.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return text.substring(prefix.length());
        }
        return text;
    }

    private static String normalizeAsset(@Nullable String value) {
        if (value == null) return "";
        String result = JAR_SUFFIX_PATTERN.matcher(value.toLowerCase(Locale.ROOT).trim()).replaceAll("");

        String previous = null;
        while (!result.equals(previous)) {
            previous = result;
            result = VERSION_SUFFIX_PATTERN.matcher(stripOwnPlatform(result)).replaceAll("");
        }
        return SEPARATORS_PATTERN.matcher(result).replaceAll("");
    }

    private static String stripOwnPlatform(String value) {
        String result = value;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String suffix : OWN_PLATFORM_SUFFIXES) {
                if (result.length() > suffix.length() && result.endsWith(suffix)) {
                    result = result.substring(0, result.length() - suffix.length());
                    stripped = true;
                }
            }
        }
        return result;
    }

    public static boolean isNonRuntimeArtifact(@Nullable String assetName) {
        if (assetName == null) return true;
        String lower = assetName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) {
            lower = lower.substring(0, lower.length() - 4);
        }

        for (String part : SPLIT_NON_RUNTIME_PATTERN.split(lower)) {
            if (NON_RUNTIME_CLASSIFIERS.contains(part) || FOREIGN_PLATFORMS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    public static double platformBonus(String assetName) {
        return platformBonus(assetName, null);
    }

    public static double platformBonus(@Nullable String assetName, ServerProfile profile) {
        if (assetName == null) return 0.0;
        String lower = assetName.toLowerCase(Locale.ROOT);

        boolean isFoliaServer = profile != null && profile.supportsLoader(Set.of("folia"));
        if (isFoliaServer) {
            if (lower.contains("folia")) return 0.10;
            if (lower.contains("paper") || lower.contains("purpur")) return 0.06;
            if (lower.contains("spigot")) return 0.04;
            if (lower.contains("bukkit")) return 0.03;
            return 0.0;
        }

        if (lower.contains("paper") || lower.contains("purpur")) return 0.08;
        if (lower.contains("spigot")) return lower.contains("legacy") ? 0.02 : 0.04;
        if (lower.contains("bukkit")) return 0.03;
        if (lower.contains("folia")) return 0.01;
        return 0.0;
    }

    public static boolean isAssetCompanion(String pluginName, String assetName) {
        String plugin = normalizeAsset(pluginName);
        String asset = normalizeAsset(assetName);
        if (plugin.isEmpty() || asset.isEmpty() || plugin.equals(asset)) {
            return false;
        }

        if (asset.startsWith(plugin)) {
            String tail = asset.substring(plugin.length());
            if (!tail.isEmpty() && COMPANION_SUFFIXES.contains(tail)) {
                return true;
            }
        }

        for (String suffix : COMPANION_SUFFIXES) {
            if (plugin.endsWith(suffix) && !asset.contains(suffix)) {
                return true;
            }
            if (asset.contains(suffix) && !plugin.contains(suffix)) {
                return true;
            }
        }

        return false;
    }

    public static double assetSimilarity(String pluginName, String assetName) {
        String plugin = normalizeAsset(pluginName);
        String asset = normalizeAsset(assetName);
        if (plugin.isEmpty() || asset.isEmpty()) return 0.0;
        if (plugin.equals(asset)) return 1.0;

        return rawSimilarity(plugin, asset);
    }

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
        } else if (normalized.startsWith("essentialsx")) {
            String sub = normalized.substring("essentialsx".length());
            String nonXVariant = "essentials" + sub;
            if (!result.contains(nonXVariant)) {
                result.add(nonXVariant);
            }
            if (!result.contains("essentials")) {
                result.add("essentials");
            }
        }

        for (Map.Entry<String, List<String>> entry : KNOWN_ALIASES.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized)) {
                for (String alias : entry.getValue()) {
                    if (!result.contains(alias)) {
                        result.add(alias);
                    }
                }
            } else {
                for (String val : entry.getValue()) {
                    if (val.equalsIgnoreCase(normalized) && !result.contains(entry.getKey())) {
                        result.add(entry.getKey());
                    }
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
        String step1 = name.toLowerCase(Locale.ROOT).trim();
        String step2 = NAME_VERSION_PATTERN_1.matcher(step1).replaceAll("");
        String step3 = NAME_VERSION_PATTERN_2.matcher(step2).replaceAll("");
        String step4 = SEPARATORS_PATTERN.matcher(step3).replaceAll("");
        return NAME_EDITION_SUFFIX_PATTERN.matcher(step4).replaceAll("").trim();
    }

    public static boolean isCompanion(String pluginName, String candidateName) {
        if (endsWithJarIgnoreCase(candidateName)) {
            return isAssetCompanion(pluginName, candidateName);
        }
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
        if (candidateName == null || candidateName.isEmpty()) {
            return false;
        }
        int len = candidateName.length();
        int i = 0;
        while (i < len) {
            while (i < len && !Character.isLetterOrDigit(candidateName.charAt(i))) {
                i++;
            }
            if (i >= len) break;
            int start = i;
            while (i < len && Character.isLetterOrDigit(candidateName.charAt(i))) {
                i++;
            }
            String token = candidateName.substring(start, i).toLowerCase(Locale.ROOT);
            if (COMPANION_MARKERS.contains(token) && !normalizedPlugin.contains(token)) {
                return true;
            }
        }
        return false;
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

        value = TITLE_DELIMITERS_PATTERN.matcher(value).replaceAll("|");

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
            String run = WHITESPACE_RUN_PATTERN.matcher(stripDecoration(value.substring(i, end))).replaceAll(" ");
            if (!run.isEmpty()) {
                if (TITLE_FILTER_CHARS_PATTERN.matcher(run).replaceAll("").isEmpty()) {
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

    public static boolean isExactOrCleanMatch(@Nullable String pluginName, @Nullable String resourceTitle) {
        return isExactOrCleanMatch(pluginName, resourceTitle, null);
    }

    public static boolean isExactOrCleanMatch(@Nullable String pluginName, @Nullable String resourceTitle, @Nullable String slug) {
        if (pluginName == null || pluginName.isBlank()) {
            return false;
        }

        String normTitle = null;
        String normPrimary = null;
        String normClean = null;
        if (resourceTitle != null && !resourceTitle.isBlank()) {
            normTitle = normalizeName(resourceTitle);
            String primary = primaryResourceName(resourceTitle);
            if (!primary.isBlank()) {
                normPrimary = normalizeName(primary);
            }
            String clean = cleanResourceTitle(resourceTitle);
            if (!clean.isBlank()) {
                normClean = normalizeName(clean);
            }
        }

        String normSlug = (slug != null && !slug.isBlank()) ? normalizeName(slug) : null;

        List<String> candidates = getSearchAliases(pluginName);
        for (String candidate : candidates) {
            String norm = normalizeName(candidate);
            if (norm.isEmpty()) continue;

            if (normTitle != null) {
                if (norm.equalsIgnoreCase(normTitle)) {
                    return true;
                }
                if (normPrimary != null && norm.equalsIgnoreCase(normPrimary)) {
                    return true;
                }
                if (normClean != null && norm.equalsIgnoreCase(normClean)) {
                    return true;
                }
            }
            if (normSlug != null && norm.equalsIgnoreCase(normSlug)) {
                return true;
            }
        }
        return false;
    }

    public static double resourceNameSimilarity(String pluginName, String resourceTitle) {
        if (pluginName == null || pluginName.isBlank() || resourceTitle == null || resourceTitle.isBlank()) {
            return 0.0;
        }
        if (isExactOrCleanMatch(pluginName, resourceTitle)) {
            return 1.0;
        }
        String normTitle = normalizeName(resourceTitle);
        String normPrimary = normalizeName(primaryResourceName(resourceTitle));

        double best = 0.0;
        for (String alias : getSearchAliases(pluginName)) {
            String norm = normalizeName(alias);
            if (norm.isEmpty()) continue;
            double full = rawSimilarity(norm, normTitle);
            double primary = rawSimilarity(norm, normPrimary);
            double score = Math.max(full, primary);
            if (score > best) {
                best = score;
            }
        }
        return best;
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
        String[] resourceParts = AUTHOR_DELIMITERS_PATTERN.split(resource);

        for (String raw : pluginAuthors) {
            if (raw == null) continue;
            for (String part : AUTHOR_SPLIT_PATTERN.split(raw)) {
                String candidate = AUTHOR_BRACKETS_PATTERN.matcher(part.toLowerCase(Locale.ROOT)).replaceAll("").trim();
                if (candidate.length() < 3) continue;
                if (resource.contains(candidate) || candidate.contains(resource)) {
                    return true;
                }
                for (String sub : AUTHOR_DELIMITERS_PATTERN.split(candidate)) {
                    if (sub.length() >= 4 && resource.contains(sub)) {
                        return true;
                    }
                }
                for (String sub : resourceParts) {
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
                for (String part : BRAND_SPLIT_PATTERN.split(author)) {
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
            for (String segment : SLASH_SPLIT_PATTERN.split(hostPath)) {
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

    public static double rawSimilarity(@Nullable String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return 0.0;
        if (left.equals(right)) return 1.0;

        double levenshtein = 1.0 - (double) distance(left, right) / Math.max(left.length(), right.length());
        return Math.max(levenshtein, jaccard(left, right));
    }

    private static double jaccard(String left, String right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        int intersection = 0;
        for (String s : a) {
            if (b.contains(s)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private static int distance(String left, String right) {
        if (left.length() < right.length()) {
            return distance(right, left);
        }
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
        String step1 = raw.trim().toLowerCase(Locale.ROOT);
        String step2 = BRAND_AT_PATTERN.matcher(step1).replaceAll("");
        String token = NON_ALPHANUMERIC_MULTI_PATTERN.matcher(step2).replaceAll("");
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
            if (installedClean.equals(remoteClean)) {
                return true;
            }
            if (installedClean.length() >= 3 && remoteClean.contains(installedClean)) {
                return true;
            }
            if (remoteClean.length() >= 3 && installedClean.contains(remoteClean)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeAuthor(@Nullable String author) {
        if (author == null) return "";
        return NON_ALPHANUMERIC_SINGLE_PATTERN.matcher(author.toLowerCase(Locale.ROOT)).replaceAll("").trim();
    }

    public static String toHex(@Nullable byte[] digest) {
        if (digest == null || digest.length == 0) return "";
        char[] out = new char[digest.length << 1];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            out[i << 1] = HEX_CHARS[b >>> 4];
            out[(i << 1) + 1] = HEX_CHARS[b & 0x0F];
        }
        return new String(out);
    }
}
