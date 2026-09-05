package ru.milkyway.plugmanreloaded.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class Log {

    private static final Logger STANDALONE_LOGGER = Logger.getLogger("PlugManReloaded");

    private static final String STANDALONE_INFO_PREFIX = "&#00C9FF▶ PlugMan &8| &f";
    private static final String STANDALONE_SUCCESS_PREFIX = "&#92FE9D▶ PlugMan &8| &f";
    private static final String STANDALONE_WARN_PREFIX = "&#FB8808▶ Warning &8| &f";
    private static final String STANDALONE_ERROR_PREFIX = "&#FF5D00▶ Oops! &8| &f";

    private static final String DEBUG_PREFIX = "&7[DEBUG] &#ffff00PlugManReloaded &8| &7";
    private static final String DEBUG_PLAIN_PREFIX = "[DEBUG] PlugManReloaded | ";

    private Log() {
    }

    public static void console(String key, String... placeholders) {
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        String message = LogCatalog.get(key, placeholders);
        if (plugin != null) {
            plugin.console(message);
        } else {
            toStandalone(STANDALONE_INFO_PREFIX + message, Level.INFO, message);
        }
    }

    public static void info(String key, String... placeholders) {
        sendInfo(LogCatalog.get(key, placeholders));
    }

    public static void success(String key, String... placeholders) {
        sendSuccess(LogCatalog.get(key, placeholders));
    }

    public static void warn(String key, String... placeholders) {
        sendWarn(LogCatalog.get(key, placeholders));
    }

    public static void warn(String key, Throwable t, String... placeholders) {
        sendWarn(withDetail(LogCatalog.get(key, placeholders), t));
        logStackTrace(Level.WARNING, key, t, false);
    }

    public static void error(String key, String... placeholders) {
        sendError(LogCatalog.get(key, placeholders));
    }

    public static void error(String key, Throwable t, String... placeholders) {
        sendError(withDetail(LogCatalog.get(key, placeholders), t));
        logStackTrace(Level.SEVERE, key, t, true);
    }

    public static void debug(String key, String... placeholders) {
        sendDebug(LogCatalog.get(key, placeholders));
    }

    public static void debug(String key, Throwable t, String... placeholders) {
        sendDebug(withDetail(LogCatalog.get(key, placeholders), t));
        logStackTrace(Level.INFO, key, t, false);
    }

    public static void debugPlain(String key, String... placeholders) {
        sendDebugPlain(LogCatalog.get(key, placeholders));
    }

    private static void sendInfo(@Nullable String message) {
        if (message == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin != null) {
            plugin.log(message);
        } else {
            toStandalone(STANDALONE_INFO_PREFIX + message, Level.INFO, message);
        }
    }

    private static void sendSuccess(@Nullable String message) {
        if (message == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin != null) {
            plugin.success(message);
        } else {
            toStandalone(STANDALONE_SUCCESS_PREFIX + message, Level.INFO, message);
        }
    }

    private static void sendWarn(@Nullable String message) {
        if (message == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin != null) {
            plugin.warn(message);
        } else {
            toStandalone(STANDALONE_WARN_PREFIX + message, Level.WARNING, message);
        }
    }

    private static void sendError(@Nullable String message) {
        if (message == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin != null) {
            plugin.error(message);
        } else {
            toStandalone(STANDALONE_ERROR_PREFIX + message, Level.SEVERE, message);
        }
    }

    private static void sendDebug(@Nullable String message) {
        if (message == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin == null || plugin.getConfigManager() == null) return;
        if (!plugin.getConfigManager().isDebugEnabled()) return;

        plugin.console(DEBUG_PREFIX + message);
    }

    private static void sendDebugPlain(@Nullable String message) {
        if (message == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin == null || plugin.getConfigManager() == null) return;
        if (!plugin.getConfigManager().isDebugEnabled()) return;
        if (Bukkit.getServer() == null) return;

        Bukkit.getConsoleSender().sendMessage(Component.text(DEBUG_PLAIN_PREFIX + message));
    }

    private static String withDetail(String message, @Nullable Throwable t) {
        if (t == null || t.getMessage() == null) {
            return message;
        }
        String detail = t.getMessage();
        return message.contains(detail) ? message : message + " (" + detail + ")";
    }

    private static void logStackTrace(Level level, String key, @Nullable Throwable t, boolean always) {
        if (t == null) return;

        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin == null) {
            STANDALONE_LOGGER.log(level, key, t);
            return;
        }
        if (!always && (plugin.getConfigManager() == null || !plugin.getConfigManager().isDebugEnabled())) {
            return;
        }
        plugin.getLogger().log(level, key, t);
    }

    private static void toStandalone(String colored, Level level, String plain) {
        if (Bukkit.getServer() == null || Bukkit.getConsoleSender() == null) {
            STANDALONE_LOGGER.log(level, HexColors.stripColors(plain));
            return;
        }
        Bukkit.getConsoleSender().sendMessage(HexColors.translateForConsole(colored));
    }
}
