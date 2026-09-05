package ru.milkyway.plugmanreloaded.configs;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Getter
public class MainConfig {

    private final PlugManReloaded plugin;
    private final File configFile;
    private FileConfiguration config;
    private String language;

    private boolean bStatsEnabled;
    private boolean updateCheckerEnabled;
    private String updateNotifyMode;
    private int updatePeriodicIntervalHours;
    private boolean consoleLogsEnabled;
    private boolean debugEnabled;
    private boolean autoSyncCommands;
    private boolean overridePluginsCommand;
    private boolean useJarFileNames;
    private boolean cascadeReloadByDefault;
    private boolean safeModeEnabled;
    private boolean hotSwapEnabled;
    private int hotSwapDebounceMs;
    private boolean hotSwapAutoUnloadOnDelete;
    private boolean hotSwapBackupOldVersion;
    private boolean hotSwapCascadeReload;
    private boolean hotSwapAllowUntrustedLoads;
    private Set<String> ignoredPlugins = new HashSet<>();
    private Set<String> unsafeToUnload = new HashSet<>();
    private boolean updatesCheckOnStart;
    private boolean allowPrerelease;
    private boolean jarScanEnabled;
    private int maxDependencyDepth;
    private String githubToken = "";
    private int updateCacheTtlHours;
    private int backupKeepDays;
    private int backupMaxPerPlugin;
    private boolean deletePluginDataFolder;
    private boolean manualSourceEnabled;
    private int manualSourceTimeoutSeconds;
    private boolean downloadSuggestionsEnabled = true;
    private boolean downloadSuggestionsHideDownloaded = true;
    private List<String> downloadSuggestedPlugins = List.of(
            "LuckPerms", "Vault", "PlaceholderAPI", "DecentHolograms", "EssentialsX",
            "WorldGuard", "WorldEdit", "ProtocolLib", "CoreProtect", "ViaVersion",
            "ViaBackwards", "Chunky", "GSit", "DeluxeMenus", "Citizens",
            "Multiverse-Core", "FastAsyncWorldEdit", "spark", "TAB", "packetevents",
            "SkinsRestorer"
    );

    private String updatesNotifyMode = "both";
    private String updatesNotifyTarget = "all";

    public MainConfig(PlugManReloaded plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        load();
    }

    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        String selectedLanguage = peekLanguage();
        String template = ConfigLocalizer.templateResourceFor(selectedLanguage);

        new ConfigUpdater(plugin).update(configFile, template != null ? template : "config.yml");
        ConfigLocalizer.apply(plugin, configFile, selectedLanguage);

        config = YamlConfiguration.loadConfiguration(configFile);
        reloadValues();
    }

    private String peekLanguage() {
        try {
            String raw = YamlConfiguration.loadConfiguration(configFile).getString("settings.language", "ru");
            if (raw.isBlank()) return "ru";
            return raw.trim().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "ru";
        }
    }

    public void reloadValues() {
        if (config == null) return;

        this.bStatsEnabled = config.getBoolean("settings.bstats.enabled", true);
        this.updateCheckerEnabled = config.getBoolean("settings.update-checker.enabled", true);
        this.updateNotifyMode = config.getString("settings.update-checker.notify-mode", "both");
        this.updatePeriodicIntervalHours = Math.max(1, config.getInt("settings.update-checker.periodic-interval-hours", 6));
        this.consoleLogsEnabled = config.getBoolean("settings.logs-in-console.enable", true);
        this.debugEnabled = config.getBoolean("settings.logs-in-console.debug", false);
        this.autoSyncCommands = config.getBoolean("settings.auto-sync-commands.enabled", true);
        this.overridePluginsCommand = config.getBoolean("settings.override-plugins-command.enabled", true);
        this.useJarFileNames = config.getBoolean("settings.use-jar-file-names.enabled", false);
        this.cascadeReloadByDefault = config.getBoolean("settings.cascade-reload.by-default", false);
        this.safeModeEnabled = config.getBoolean("settings.safe-mode.enabled", true);
        this.hotSwapEnabled = config.getBoolean("settings.hot-swap.enabled", false);
        this.hotSwapDebounceMs = Math.max(100, config.getInt("settings.hot-swap.debounce-ms", 500));
        this.hotSwapAutoUnloadOnDelete = config.getBoolean("settings.hot-swap.auto-unload-on-delete", true);
        this.hotSwapBackupOldVersion = config.getBoolean("settings.hot-swap.backup-old-version", true);
        this.hotSwapCascadeReload = config.getBoolean("settings.hot-swap.cascade-reload", true);
        this.hotSwapAllowUntrustedLoads = config.getBoolean("settings.hot-swap.allow-untrusted-loads", false);

        this.language = config.getString("settings.language", "ru").trim().toLowerCase(Locale.ROOT);
        if (this.language.isBlank()) {
            this.language = "ru";
        }

        List<String> ignored = config.getStringList("settings.ignored-plugins");
        this.ignoredPlugins = new HashSet<>();
        for (String s : ignored) {
            this.ignoredPlugins.add(s.toLowerCase(Locale.ROOT));
        }

        this.ignoredPlugins.add(plugin.getName().toLowerCase(Locale.ROOT));
        this.ignoredPlugins.add("plugmanreloaded");

        this.updatesCheckOnStart = config.getBoolean("settings.updates.check-on-start", true);
        this.updatesNotifyMode = config.getString("settings.updates.notify-mode", "both");
        this.updatesNotifyTarget = config.getString("settings.updates.notify-target", "all");
        this.allowPrerelease = config.getBoolean("settings.updates.allow-prerelease", false);
        this.githubToken = config.getString("settings.updates.github-token", "");
        this.maxDependencyDepth = Math.max(0, config.getInt("settings.download.max-dependency-depth", 3));
        this.updateCacheTtlHours = Math.max(1, config.getInt("settings.updates.cache-ttl-hours", 6));
        this.backupKeepDays = Math.max(1, config.getInt("settings.backups.keep-days", 1));
        this.backupMaxPerPlugin = Math.max(1, config.getInt("settings.backups.max-backups-per-plugin", 3));
        this.deletePluginDataFolder = config.getBoolean("settings.delete.delete-data-folder", false);
        this.jarScanEnabled = config.getBoolean("settings.updates.scan-jar-references", true);
        this.manualSourceEnabled = config.getBoolean("settings.updates.manual-source.enabled", true);
        this.manualSourceTimeoutSeconds = Math.max(5, config.getInt("settings.updates.manual-source.timeout-seconds", 60));

        this.downloadSuggestionsEnabled = config.getBoolean("settings.download.suggestions.enabled", true);
        this.downloadSuggestionsHideDownloaded = config.getBoolean("settings.download.suggestions.hide-downloaded", true);
        List<String> suggestions = config.getStringList("settings.download.suggestions.plugins");
        if (!suggestions.isEmpty()) {
            this.downloadSuggestedPlugins = suggestions;
        }

        List<String> unsafe = config.getStringList("settings.unsafe-to-unload");
        this.unsafeToUnload = new HashSet<>();
        for (String s : unsafe) {
            this.unsafeToUnload.add(s.toLowerCase(Locale.ROOT));
        }
    }

    public boolean isPluginIgnored(@Nullable String pluginName) {
        if (pluginName == null) return true;
        return ignoredPlugins.contains(pluginName.toLowerCase(Locale.ROOT));
    }

    public String getLanguage() {
        return language;
    }

    public String getUpdatesNotifyMode() {
        return updatesNotifyMode != null ? updatesNotifyMode : "both";
    }

    public String getUpdatesNotifyTarget() {
        return updatesNotifyTarget != null ? updatesNotifyTarget : "all";
    }

    public boolean isUnsafeToUnload(@Nullable String pluginName) {
        if (pluginName == null) return false;
        return unsafeToUnload.contains(pluginName.toLowerCase(Locale.ROOT));
    }
}

