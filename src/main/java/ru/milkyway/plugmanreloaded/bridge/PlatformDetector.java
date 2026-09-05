package ru.milkyway.plugmanreloaded.bridge;

import org.bukkit.Bukkit;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.LogCatalog;

import java.util.ArrayList;
import java.util.List;

public final class PlatformDetector {

    private static final boolean FOLIA;
    private static final boolean PAPER;
    private static final boolean MODERN_PAPER;
    private static final boolean PURPUR;

    private static final List<String> PROBE_FAILURES = new ArrayList<>();

    static {
        FOLIA = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        PAPER = classPresent("com.destroystokyo.paper.PaperConfig",
                "io.papermc.paper.configuration.Configuration");
        MODERN_PAPER = classPresent("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
        PURPUR = classPresent("org.purpurmc.purpur.PurpurConfig");
    }

    private static boolean classPresent(String... candidates) {
        for (String name : candidates) {
            try {
                Class.forName(name);
                return true;
            } catch (ClassNotFoundException | NoClassDefFoundError absent) {
                continue;
            } catch (Throwable t) {
                PROBE_FAILURES.add(name + " -> " + t);
            }
        }
        return false;
    }

    public static void flushDiagnostics() {
        if (PROBE_FAILURES.isEmpty()) {
            return;
        }
        for (String failure : PROBE_FAILURES) {
            Log.warn("platformdetector.probe-failed", "failure", failure);
        }
        PROBE_FAILURES.clear();
    }

    private PlatformDetector() {}

    public static boolean isFolia() {
        return FOLIA;
    }

    public static boolean isPaper() {
        return PAPER;
    }

    public static boolean isPurpur() {
        return PURPUR;
    }

    public static boolean isModernPaper() {
        return MODERN_PAPER;
    }

    public static String getPlatformName() {
        if (FOLIA) return "Folia";
        try {
            if (Bukkit.getServer() != null) {
                String serverName = Bukkit.getName();
                if (serverName != null && !serverName.isBlank() && !serverName.equalsIgnoreCase("CraftBukkit")) {
                    return serverName;
                }
            }
        } catch (Throwable t) {
            Log.debug("platformdetector.getname-failed", t);
        }
        if (PURPUR) return "Purpur";
        if (MODERN_PAPER) return "Modern Paper (1.19.3+)";
        if (PAPER) return "Paper";
        return LogCatalog.get("platformdetector.unsupported-core");
    }
}

