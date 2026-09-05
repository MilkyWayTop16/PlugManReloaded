package ru.milkyway.plugmanreloaded.utils;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.configs.ConfigUpdater;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class LogCatalog {

    private LogCatalog() {}

    private static volatile FileConfiguration ruCatalog = loadFromClasspath("ru-logs.yml");
    private static volatile FileConfiguration enCatalog = loadFromClasspath("en-logs.yml");

    private static final String LOGS_DIR = "messages/logs";

    private static FileConfiguration loadFromClasspath(String name) {
        try (InputStream in = LogCatalog.class.getResourceAsStream("/" + LOGS_DIR + "/" + name)) {
            if (in == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception t) {
            return new YamlConfiguration();
        }
    }

    public static void reload(@Nullable PlugManReloaded plugin) {
        if (plugin == null) return;
        try {
            File dir = new File(plugin.getDataFolder(), LOGS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File ruFile = new File(dir, "ru-logs.yml");
            if (!ruFile.exists()) plugin.saveResource(LOGS_DIR + "/ru-logs.yml", false);
            File enFile = new File(dir, "en-logs.yml");
            if (!enFile.exists()) plugin.saveResource(LOGS_DIR + "/en-logs.yml", false);

            ConfigUpdater updater = new ConfigUpdater(plugin);
            updater.update(ruFile, LOGS_DIR + "/ru-logs.yml");
            updater.update(enFile, LOGS_DIR + "/en-logs.yml");

            ruCatalog = YamlConfiguration.loadConfiguration(ruFile);
            enCatalog = YamlConfiguration.loadConfiguration(enFile);
            earlyLanguage = null;
        } catch (Exception t) {
            Log.warn("logcatalog.reload-failed", t);
        }
    }

    public static String get(String key, String... placeholders) {
        String lang = currentLanguage();
        FileConfiguration primary = "en".equals(lang) ? enCatalog : ruCatalog;
        String template = primary.getString("logs." + key);
        if (template == null) {
            template = ruCatalog.getString("logs." + key);
        }
        if (template == null) {
            return key;
        }
        return apply(template, placeholders);
    }

    private static volatile String earlyLanguage;

    private static String currentLanguage() {
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        if (plugin == null) return "ru";

        if (plugin.getConfigManager() != null && plugin.getConfigManager().getMainConfig() != null) {
            String lang = plugin.getConfigManager().getMainConfig().getLanguage();
            if (lang != null && !lang.isBlank()) return lang;
        }
        return earlyLanguage(plugin);
    }

    private static String earlyLanguage(PlugManReloaded plugin) {
        String cached = earlyLanguage;
        if (cached != null) return cached;

        String lang = "ru";
        try {
            File config = new File(plugin.getDataFolder(), "config.yml");
            if (config.isFile()) {
                String raw = YamlConfiguration.loadConfiguration(config).getString("settings.language", "ru");
                if (!raw.isBlank()) {
                    lang = raw.trim().toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) {
        }
        earlyLanguage = lang;
        return lang;
    }

    private static String apply(String text, @Nullable String[] placeholders) {
        if (placeholders == null || placeholders.length == 0) return text;
        String result = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] != null ? placeholders[i + 1] : "";
            result = result.replace("{" + placeholders[i] + "}", value);
        }
        return result;
    }
}

