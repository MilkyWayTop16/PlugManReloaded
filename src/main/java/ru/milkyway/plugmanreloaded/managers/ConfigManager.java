package ru.milkyway.plugmanreloaded.managers;

import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.configs.ActionManager;
import ru.milkyway.plugmanreloaded.configs.MainConfig;

import org.bukkit.configuration.file.YamlConfiguration;
import ru.milkyway.plugmanreloaded.configs.ConfigUpdater;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.LogCatalog;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Getter
public final class ConfigManager {

    private final PlugManReloaded plugin;
    private final MainConfig mainConfig;
    private final ActionManager actionManager;
    private FileConfiguration messagesConfig;

    public ConfigManager(PlugManReloaded plugin) {
        this.plugin = plugin;
        this.mainConfig = plugin != null ? new MainConfig(plugin) : null;
        this.actionManager = new ActionManager(plugin, this);
        loadMessages();
    }

    private void loadMessages() {
        String lang = mainConfig != null ? mainConfig.getLanguage() : "ru";

        if (plugin == null || plugin.getDataFolder() == null) {
            messagesConfig = new YamlConfiguration();
            FileConfiguration defaults = resolveMessageDefaults(lang);
            if (defaults != null) {
                messagesConfig.setDefaults(defaults);
            }
            actionManager.loadActions(messagesConfig, lang);
            return;
        }

        File messagesDir = new File(plugin.getDataFolder(), "messages");
        if (!messagesDir.exists()) {
            messagesDir.mkdirs();
        }

        File ruFile = new File(messagesDir, "ru-messages.yml");
        if (!ruFile.exists()) plugin.saveResource("messages/ru-messages.yml", false);
        File enFile = new File(messagesDir, "en-messages.yml");
        if (!enFile.exists()) plugin.saveResource("messages/en-messages.yml", false);

        ConfigUpdater updater = new ConfigUpdater(plugin);
        updater.update(ruFile, "messages/ru-messages.yml");
        updater.update(enFile, "messages/en-messages.yml");

        File langFile = new File(messagesDir, lang + "-messages.yml");
        if (!langFile.exists()) langFile = new File(messagesDir, lang + ".yml");
        if (!langFile.exists()) {
            langFile = "ru".equals(lang) ? ruFile : (enFile.exists() ? enFile : ruFile);
        }

        messagesConfig = YamlConfiguration.loadConfiguration(langFile);
        FileConfiguration defaults = resolveMessageDefaults(lang);
        if (defaults != null) {
            messagesConfig.setDefaults(defaults);
        }

        actionManager.loadActions(messagesConfig, lang);

        LogCatalog.reload(plugin);
    }

    public FileConfiguration resolveMessageDefaults(String lang) {
        String cleanLang = lang != null && !lang.isBlank() ? lang.trim().toLowerCase(Locale.ROOT) : "ru";

        FileConfiguration ruDefault = ConfigUpdater.getDefaultConfig(plugin, "messages/ru-messages.yml");
        FileConfiguration enDefault = ConfigUpdater.getDefaultConfig(plugin, "messages/en-messages.yml");

        YamlConfiguration defaults = new YamlConfiguration();
        if (ruDefault != null) {
            for (String key : ruDefault.getKeys(true)) {
                if (!ruDefault.isConfigurationSection(key)) {
                    defaults.set(key, ruDefault.get(key));
                }
            }
        }

        if (!"ru".equals(cleanLang)) {
            if (enDefault != null) {
                for (String key : enDefault.getKeys(true)) {
                    if (!enDefault.isConfigurationSection(key)) {
                        defaults.set(key, enDefault.get(key));
                    }
                }
            }

            if (!"en".equals(cleanLang)) {
                FileConfiguration custom = ConfigUpdater.getDefaultConfig(plugin, "messages/" + cleanLang + "-messages.yml");
                if (custom == null) {
                    custom = ConfigUpdater.getDefaultConfig(plugin, "messages/" + cleanLang + ".yml");
                }
                if (custom != null) {
                    for (String key : custom.getKeys(true)) {
                        if (!custom.isConfigurationSection(key)) {
                            defaults.set(key, custom.get(key));
                        }
                    }
                }
            }
        }

        return defaults;
    }

    public boolean reload() {
        try {
            Log.info("configmanager.reloading");
            mainConfig.load();

            loadMessages();

            plugin.getUpdateChecker().reload();
            plugin.getUpdateService().reload();
            plugin.getHotSwapManager().reload();
            plugin.getPluginLifecycleManager().getJarIndex().invalidate();
            Log.info("configmanager.reloaded");
            return true;
        } catch (Exception e) {
            Log.error("configmanager.reload-error", "error", e.getMessage());
            return false;
        }
    }

    public FileConfiguration getConfig() {
        return mainConfig != null ? mainConfig.getConfig() : null;
    }

    public FileConfiguration getMessagesConfig() {
        if (messagesConfig != null) {
            return messagesConfig;
        }
        return mainConfig != null ? mainConfig.getConfig() : new YamlConfiguration();
    }

    public String text(String key, String... placeholders) {
        String value = getMessagesConfig().getString(key);
        if (value == null) {
            return key;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return value;
    }

    public void executeActions(Player player, String path) {
        actionManager.executeActions(player, path);
    }

    public void executeActions(Player player, String path, Map<String, String> placeholders) {
        actionManager.executeActions(player, path, placeholders);
    }

    public void executeActions(CommandSender sender, String path) {
        actionManager.executeActions(sender, path);
    }

    public void executeActions(CommandSender sender, String path, Map<String, String> placeholders) {
        actionManager.executeActions(sender, path, placeholders);
    }

    public boolean isBStatsEnabled() {
        return mainConfig.isBStatsEnabled();
    }

    public boolean isUpdateCheckerEnabled() {
        return mainConfig.isUpdateCheckerEnabled();
    }

    public String getUpdateNotifyMode() {
        return mainConfig.getUpdateNotifyMode();
    }

    public int getUpdatePeriodicIntervalHours() {
        return mainConfig.getUpdatePeriodicIntervalHours();
    }

    public boolean isConsoleLogsEnabled() {
        return mainConfig.isConsoleLogsEnabled();
    }

    public boolean isDebugEnabled() {
        return mainConfig.isDebugEnabled();
    }

    public boolean isAutoSyncCommands() {
        return mainConfig.isAutoSyncCommands();
    }

    public boolean isUseJarFileNames() {
        return mainConfig.isUseJarFileNames();
    }

    public boolean isCascadeReloadByDefault() {
        return mainConfig.isCascadeReloadByDefault();
    }

    public boolean isSafeModeEnabled() {
        return mainConfig.isSafeModeEnabled();
    }

    public boolean isHotSwapEnabled() {
        return mainConfig.isHotSwapEnabled();
    }

    public int getHotSwapDebounceMs() {
        return mainConfig.getHotSwapDebounceMs();
    }

    public boolean isHotSwapAutoUnloadOnDelete() {
        return mainConfig.isHotSwapAutoUnloadOnDelete();
    }

    public boolean isHotSwapBackupOldVersion() {
        return mainConfig.isHotSwapBackupOldVersion();
    }

    public boolean isHotSwapCascadeReload() {
        return mainConfig.isHotSwapCascadeReload();
    }

    public boolean isHotSwapAllowUntrustedLoads() {
        return mainConfig.isHotSwapAllowUntrustedLoads();
    }

    public Set<String> getIgnoredPlugins() {
        return mainConfig.getIgnoredPlugins();
    }

    public boolean isOverridePluginsCommand() {
        return mainConfig.isOverridePluginsCommand();
    }

    public boolean isPluginIgnored(String pluginName) {
        return mainConfig.isPluginIgnored(pluginName);
    }

    public boolean isUnsafeToUnload(String pluginName) {
        return mainConfig.isUnsafeToUnload(pluginName);
    }

    public boolean isUpdatesCheckOnStart() {
        return mainConfig.isUpdatesCheckOnStart();
    }

    public String getUpdatesNotifyMode() {
        return mainConfig.getUpdatesNotifyMode();
    }

    public String getUpdatesNotifyTarget() {
        return mainConfig.getUpdatesNotifyTarget();
    }

    public int getMaxDependencyDepth() {
        return mainConfig.getMaxDependencyDepth();
    }

    public boolean isJarScanEnabled() {
        return mainConfig.isJarScanEnabled();
    }

    public boolean isAllowPrerelease() {
        return mainConfig.isAllowPrerelease();
    }

    public String getGithubToken() {
        return mainConfig.getGithubToken();
    }

    public int getUpdateCacheTtlHours() {
        return mainConfig.getUpdateCacheTtlHours();
    }

    public int getBackupKeepDays() {
        return mainConfig.getBackupKeepDays();
    }

    public int getBackupMaxPerPlugin() {
        return mainConfig.getBackupMaxPerPlugin();
    }

    public boolean isDeletePluginDataFolder() {
        return mainConfig.isDeletePluginDataFolder();
    }

    public boolean isManualSourceEnabled() {
        return mainConfig.isManualSourceEnabled();
    }

    public int getManualSourceTimeoutSeconds() {
        return mainConfig.getManualSourceTimeoutSeconds();
    }
}

