package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AssetUtil {

    private AssetUtil() {}

    private static final Set<String> NON_RUNTIME_CLASSIFIERS = Set.of(
            "javadoc", "sources", "source", "plain", "original", "tests", "test", "shaded-sources", "cli"
    );

    private static final Set<String> FOREIGN_PLATFORMS = Set.of(
            "bungee", "bungeecord", "velocity", "waterfall", "fabric", "forge", "neoforge", "quilt", "sponge", "nukkit"
    );

    private static final List<String> OWN_PLATFORMS = List.of("paper", "purpur", "spigot", "bukkit", "folia");

    private static final List<String> SEPARATORS = List.of("-", "_", ".");


    private static final String VERSION_SUFFIX = "[-_.\\s]+v?\\d[a-z0-9.+_-]*$";

    private static String normalize(@Nullable String value) {
        if (value == null) return "";
        String result = value.toLowerCase(Locale.ROOT).trim().replaceAll("\\.jar$", "");

        String previous = null;
        while (!result.equals(previous)) {
            previous = result;
            result = stripOwnPlatform(result).replaceAll(VERSION_SUFFIX, "");
        }
        return result.replaceAll("[-_.\\s]+", "");
    }

    private static String stripOwnPlatform(String value) {
        String result = value;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String platform : OWN_PLATFORMS) {
                for (String separator : SEPARATORS) {
                    String suffix = separator + platform;
                    if (result.length() > suffix.length() && result.endsWith(suffix)) {
                        result = result.substring(0, result.length() - suffix.length());
                        stripped = true;
                    }
                }
            }
        }
        return result;
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

    public static boolean isCompanion(String pluginName, String assetName) {
        String plugin = normalize(pluginName);
        String asset = normalize(assetName);
        if (plugin.isEmpty() || asset.isEmpty() || plugin.equals(asset)) {
            return false;
        }

        if (asset.startsWith(plugin)) {
            String tail = asset.substring(plugin.length());
            if (!tail.isEmpty() && NameUtil.COMPANION_SUFFIXES.contains(tail)) {
                return true;
            }
        }

        for (String suffix : NameUtil.COMPANION_SUFFIXES) {
            if (plugin.endsWith(suffix) && !asset.contains(suffix)) {
                return true;
            }
            if (asset.contains(suffix) && !plugin.contains(suffix)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isNonRuntimeArtifact(@Nullable String assetName) {
        if (assetName == null) return true;
        String lower = assetName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) {
            lower = lower.substring(0, lower.length() - 4);
        }

        for (String part : lower.split("[-_.]")) {
            if (NON_RUNTIME_CLASSIFIERS.contains(part) || FOREIGN_PLATFORMS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    public static double similarity(String pluginName, String assetName) {
        String plugin = normalize(pluginName);
        String asset = normalize(assetName);
        if (plugin.isEmpty() || asset.isEmpty()) return 0.0;
        if (plugin.equals(asset)) return 1.0;

        return NameUtil.similarity(plugin, asset);
    }

}

