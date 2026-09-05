package ru.milkyway.plugmanreloaded.update;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerProfile {

    private static final Pattern VERSION_IN_BUKKIT_STRING = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)");

    private final String minecraftVersion;
    private final Set<String> loaders;
    private final int javaVersion;

    private ServerProfile(String minecraftVersion, Set<String> loaders, int javaVersion) {
        this.minecraftVersion = minecraftVersion;
        this.loaders = loaders;
        this.javaVersion = javaVersion;
    }

    public static ServerProfile of(String minecraftVersion, Set<String> loaders) {
        return new ServerProfile(minecraftVersion == null ? "" : minecraftVersion.trim(),
                loaders == null ? Set.of() : loaders, detectJavaVersion());
    }

    public static ServerProfile of(String minecraftVersion, Set<String> loaders, int javaVersion) {
        return new ServerProfile(minecraftVersion == null ? "" : minecraftVersion.trim(),
                loaders == null ? Set.of() : loaders, javaVersion);
    }

    public static ServerProfile detect() {
        return of(detectMinecraftVersion(), detectLoaders(), detectJavaVersion());
    }

    public static int detectJavaVersion() {
        try {
            return Runtime.version().feature();
        } catch (Throwable t) {
            String spec = System.getProperty("java.specification.version");
            if (spec != null && !spec.isBlank()) {
                try {
                    if (spec.startsWith("1.")) {
                        return Integer.parseInt(spec.substring(2));
                    }
                    return Integer.parseInt(spec.split("\\.")[0]);
                } catch (Throwable parse) {
                    Log.debug("serverprofile.java-version-parse-failed", parse, "spec", spec);
                }
            }
            return 16;
        }
    }

    private static String detectMinecraftVersion() {
        try {
            String direct = Bukkit.getMinecraftVersion();
            if (direct != null && !direct.isBlank()) {
                return direct.trim();
            }
        } catch (NoSuchMethodError e) {
            Log.debug("serverprofile.getminecraftversion-missing");
        } catch (Throwable t) {
            Log.debug("serverprofile.getminecraftversion-failed", t);
        }

        String bukkitVersion = Bukkit.getBukkitVersion();
        if (bukkitVersion != null) {
            Matcher matcher = VERSION_IN_BUKKIT_STRING.matcher(bukkitVersion.trim());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        Log.warn("serverprofile.version-detect-failed");
        return "";
    }

    private static Set<String> detectLoaders() {
        Set<String> result = new LinkedHashSet<>();
        result.add("paper");
        result.add("spigot");
        result.add("bukkit");
        if (PlatformDetector.isFolia()) {
            result.add("folia");
        }
        if (PlatformDetector.isPurpur()) {
            result.add("purpur");
        }
        return result;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public Set<String> loaders() {
        return loaders;
    }

    public int javaVersion() {
        return javaVersion;
    }

    public boolean versionKnown() {
        return minecraftVersion != null && !minecraftVersion.isBlank();
    }

    public boolean supportsLoader(@Nullable Set<String> versionLoaders) {
        if (versionLoaders == null || versionLoaders.isEmpty()) {
            return true;
        }
        for (String loader : versionLoaders) {
            if (loaders.contains(loader.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

