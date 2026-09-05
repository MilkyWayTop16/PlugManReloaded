package ru.milkyway.plugmanreloaded;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.api.PlugManAPI;
import ru.milkyway.plugmanreloaded.api.PlugManProvider;
import ru.milkyway.plugmanreloaded.api.impl.PlugManAPIImpl;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.commands.CommandsHandler;
import ru.milkyway.plugmanreloaded.commands.CommandsTabCompleter;
import ru.milkyway.plugmanreloaded.download.DownloadService;
import ru.milkyway.plugmanreloaded.listeners.CommandOverrideListener;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import ru.milkyway.plugmanreloaded.managers.HotSwapManager;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.update.UpdateService;
import ru.milkyway.plugmanreloaded.update.input.ManualSourceListener;
import ru.milkyway.plugmanreloaded.update.input.ManualSources;
import ru.milkyway.plugmanreloaded.utils.BStats;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.LogCatalog;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;
import ru.milkyway.plugmanreloaded.utils.UpdateChecker;

@Getter
public final class PlugManReloaded extends JavaPlugin {

    @Getter
    private static PlugManReloaded instance;

    private ConfigManager configManager;
    private LifecycleManager pluginLifecycleManager;
    private ConfirmationManager confirmationManager;
    private HotSwapManager hotSwapManager;
    private UpdateChecker updateChecker;
    private UpdateService updateService;
    private DownloadService downloadService;
    private ManualSources manualSources;
    private CommandsHandler commandsHandler;
    private PlugManAPI api;
    private boolean initialized = false;

    @Override
    public void onEnable() {
        instance = this;

        if (!PlatformDetector.isPaper()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        long startTime = System.currentTimeMillis();

        if (!initializePlugin()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.initialized = true;

        if (configManager.isBStatsEnabled()) {
            new BStats(this);
        }

        long loadTime = System.currentTimeMillis() - startTime;
        logStartupInfo(loadTime);
    }

    private boolean initializePlugin() {
        try {
            console("&f");
            Log.console("startup.reading-config");
            configManager = new ConfigManager(this);

            Log.console("startup.detecting-platform", "platform", PlatformDetector.getPlatformName());
            PlatformDetector.flushDiagnostics();

            Log.console("startup.init-core");
            pluginLifecycleManager = new LifecycleManager(this);
            confirmationManager = new ConfirmationManager(this);
            getServer().getPluginManager().registerEvents(confirmationManager, this);

            Log.console("startup.registering-commands");
            commandsHandler = new CommandsHandler(this);
            CommandsTabCompleter tabCompleter = new CommandsTabCompleter(this, commandsHandler);

            registerCommand("plugmanreloaded", commandsHandler, tabCompleter);

            Log.console("startup.init-hotswap");
            hotSwapManager = new HotSwapManager(this, pluginLifecycleManager);

            Log.console("startup.init-updates");
            updateService = new UpdateService(this);
            getServer().getPluginManager().registerEvents(updateService, this);
            updateService.checkOnStartIfEnabled();
            String downloadUserAgent = "PlugManReloaded/" + PluginMetaHelper.getVersion(this);
            downloadService = new DownloadService(this, updateService.getServerProfile(), updateService.getCatalog(), downloadUserAgent);
            manualSources = new ManualSources(this);
            getServer().getPluginManager().registerEvents(new ManualSourceListener(this, manualSources), this);
            updateChecker = new UpdateChecker(this);
            getServer().getPluginManager().registerEvents(updateChecker, this);
            getServer().getPluginManager().registerEvents(new CommandOverrideListener(this), this);

            Log.console("startup.registering-api");
            api = new PlugManAPIImpl(this);
            PlugManProvider.register(api);
            getServer().getServicesManager().register(PlugManAPI.class, api, this, ServicePriority.Normal);

            return true;
        } catch (Exception | LinkageError t) {
            Log.error("startup.init-failed", t, "error", String.valueOf(t.getMessage()));
            return false;
        }
    }

    private void registerCommand(String name, CommandsHandler handler, CommandsTabCompleter completer) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(completer);
        }
    }

    private void logStartupInfo(long loadTime) {
        console("&#ffff00 ");
        console("&#ffff00  █▀█ █░░ █░█ █▀▀ █▀▄▀█ ▄▀█ █▄░█ █▀█ █▀▀ █░░ █▀█ ▄▀█ █▀▄ █▀▀ █▀▄");
        console("&#ffff00  █▀▀ █▄▄ █▄█ █▄█ █░▀░█ █▀█ █░▀█ █▀▄ ██▄ █▄▄ █▄█ █▀█ █▄▀ ██▄ █▄▀");
        console("&#ffff00 ");
        console("&f                     (By MilkyWay for everyone)");
        console("&#ffff00 ");
        Log.console("startup.banner-enabled");
        console("&#ffff00 ");
        Log.console("startup.banner-version", "version", PluginMetaHelper.getVersion(this));
        Log.console("startup.banner-platform", "platform", PlatformDetector.getPlatformName());
        Log.console("startup.banner-plugins", "count", String.valueOf(Bukkit.getPluginManager().getPlugins().length));
        Log.console("startup.banner-time", "ms", String.valueOf(loadTime));
        console("&#ffff00 ");
    }

    @Override
    public void onDisable() {
        long startTime = System.currentTimeMillis();

        if (initialized) {
            Log.console("shutdown.starting");
        }

        if (hotSwapManager != null) {
            if (initialized) Log.console("shutdown.stopping-hotswap");
            hotSwapManager.stop();
        }

        if (updateChecker != null) {
            if (initialized) Log.console("shutdown.stopping-updatechecker");
            updateChecker.shutdown();
        }

        if (confirmationManager != null) {
            confirmationManager.shutdown();
        }

        if (updateService != null) {
            updateService.shutdown();
        }

        if (manualSources != null) {
            manualSources.shutdown();
        }

        if (initialized) {
            Log.console("shutdown.clearing-caches");
        }
        getServer().getServicesManager().unregisterAll(this);
        PlugManProvider.unregister();
        api = null;
        ReflectionHelper.clearCache();

        if (!initialized) {
            instance = null;
            return;
        }

        long unloadTime = System.currentTimeMillis() - startTime;
        logShutdownInfo(unloadTime);
        instance = null;
    }

    private void logShutdownInfo(long unloadTime) {
        console("&#ffff00 ");
        console("&#ffff00  █▀█ █░░ █░█ █▀▀ █▀▄▀█ ▄▀█ █▄░█ █▀█ █▀▀ █░░ █▀█ ▄▀█ █▀▄ █▀▀ █▀▄");
        console("&#ffff00  █▀▀ █▄▄ █▄█ █▄█ █░▀░█ █▀█ █░▀█ █▀▄ ██▄ █▄▄ █▄█ █▀█ █▄▀ ██▄ █▄▀");
        console("&#ffff00 ");
        console("&f                     (By MilkyWay for everyone)");
        console("&#ffff00 ");
        Log.console("shutdown.banner-disabled");
        console("&#ffff00 ");
        Log.console("shutdown.banner-version", "version", PluginMetaHelper.getVersion(this));
        Log.console("shutdown.banner-time", "ms", String.valueOf(unloadTime));
        console("&#ffff00 ");
    }

    public void console(@Nullable String message) {
        if (message == null) return;
        Bukkit.getConsoleSender().sendMessage(HexColors.translateForConsole(message));
    }

    public void log(String message) {
        if (configManager != null && configManager.isConsoleLogsEnabled()) {
            console(LogCatalog.get("prefix.info") + message);
        }
    }

    public void success(String message) {
        if (configManager != null && configManager.isConsoleLogsEnabled()) {
            console(LogCatalog.get("prefix.success") + message);
        }
    }

    public void info(String message) {
        log(message);
    }

    public void warn(String message) {
        Bukkit.getConsoleSender().sendMessage(HexColors.translateForConsole(LogCatalog.get("prefix.warn") + message));
    }

    public void error(String message) {
        Bukkit.getConsoleSender().sendMessage(HexColors.translateForConsole(LogCatalog.get("prefix.error") + message));
    }

    @Override
    public FileConfiguration getConfig() {
        if (configManager != null && configManager.getMainConfig() != null) {
            FileConfiguration cfg = configManager.getMainConfig().getConfig();
            if (cfg != null) return cfg;
        }
        return super.getConfig();
    }

    @Override
    public void reloadConfig() {
        if (configManager != null) {
            configManager.reload();
        } else {
            super.reloadConfig();
        }
    }
}

