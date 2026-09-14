package ru.milkyway.plugmanreloaded.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

import java.io.File;
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

    private static volatile Boolean earlyConsoleLogs;
    private static volatile Boolean earlyDebug;

    private Log() {
    }

    public static void console(String key, String... placeholders) {
        String message = LogCatalog.get(key, placeholders);
        sendRaw(message, Level.INFO, message);
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

    public static boolean isConsoleLogsEnabled() {
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin != null && plugin.getConfigManager() != null) {
            return plugin.getConfigManager().isConsoleLogsEnabled();
        }
        if (plugin != null) {
            return earlyConsoleLogsEnabled(plugin);
        }
        return false;
    }

    private static boolean earlyConsoleLogsEnabled(PlugManReloaded plugin) {
        Boolean cached = earlyConsoleLogs;
        if (cached != null) return cached;

        boolean enabled = false;
        try {
            File config = new File(plugin.getDataFolder(), "config.yml");
            if (config.isFile()) {
                enabled = YamlConfiguration.loadConfiguration(config)
                        .getBoolean("settings.logs-in-console.enable", false);
            }
        } catch (Exception ignored) {
        }
        earlyConsoleLogs = enabled;
        return enabled;
    }

    public static boolean isDebugEnabled() {
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin != null && plugin.getConfigManager() != null) {
            return plugin.getConfigManager().isDebugEnabled();
        }
        if (plugin != null) {
            return earlyDebugEnabled(plugin);
        }
        return false;
    }

    private static boolean earlyDebugEnabled(PlugManReloaded plugin) {
        Boolean cached = earlyDebug;
        if (cached != null) return cached;

        boolean enabled = false;
        try {
            File config = new File(plugin.getDataFolder(), "config.yml");
            if (config.isFile()) {
                enabled = YamlConfiguration.loadConfiguration(config)
                        .getBoolean("settings.logs-in-console.debug", false);
            }
        } catch (Exception ignored) {
        }
        earlyDebug = enabled;
        return enabled;
    }

    public static void invalidateEarlyCache() {
        earlyConsoleLogs = null;
        earlyDebug = null;
    }

    private static void sendInfo(@Nullable String message) {
        if (message == null) return;
        if (!isConsoleLogsEnabled()) {
            return;
        }
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        send(plugin != null ? LogCatalog.get("prefix.info") + message : null,
                STANDALONE_INFO_PREFIX + message, Level.INFO, message);
    }

    private static void sendSuccess(@Nullable String message) {
        if (message == null) return;
        if (!isConsoleLogsEnabled()) {
            return;
        }
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        send(plugin != null ? LogCatalog.get("prefix.success") + message : null,
                STANDALONE_SUCCESS_PREFIX + message, Level.INFO, message);
    }

    private static void sendWarn(@Nullable String message) {
        if (message == null) return;
        send(LogCatalog.get("prefix.warn") + message,
                STANDALONE_WARN_PREFIX + message, Level.WARNING, message);
    }

    private static void sendError(@Nullable String message) {
        if (message == null) return;
        send(LogCatalog.get("prefix.error") + message,
                STANDALONE_ERROR_PREFIX + message, Level.SEVERE, message);
    }

    private static void sendDebug(@Nullable String message) {
        if (message == null) return;
        if (!isDebugEnabled()) {
            return;
        }
        sendRaw(DEBUG_PREFIX + message, Level.INFO, message);
    }

    private static void sendDebugPlain(@Nullable String message) {
        if (message == null) return;
        if (!isDebugEnabled()) {
            return;
        }
        if (Bukkit.getServer() == null || Bukkit.getConsoleSender() == null) {
            STANDALONE_LOGGER.log(Level.INFO, DEBUG_PLAIN_PREFIX + message);
            return;
        }
        Bukkit.getConsoleSender().sendMessage(Component.text(DEBUG_PLAIN_PREFIX + message));
    }

    private static void send(@Nullable String pluginMsg, String standaloneMsg, Level level, String plain) {
        String toSend = pluginMsg != null ? pluginMsg : standaloneMsg;
        sendRaw(toSend, level, plain);
    }

    private static void sendRaw(String colored, Level level, String plain) {
        if (Bukkit.getServer() == null || Bukkit.getConsoleSender() == null) {
            STANDALONE_LOGGER.log(level, HexColors.stripColors(plain));
            return;
        }
        Bukkit.getConsoleSender().sendMessage(HexColors.translateForConsole(colored));
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
        if (!always && !isDebugEnabled()) {
            return;
        }
        plugin.getLogger().log(level, key, t);
    }
}
