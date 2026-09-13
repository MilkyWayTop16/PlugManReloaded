package ru.milkyway.plugmanreloaded;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.milkyway.plugmanreloaded.api.PlugManAPI;
import ru.milkyway.plugmanreloaded.api.PlugManProvider;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.commands.CommandHandler;
import ru.milkyway.plugmanreloaded.commands.CommandTabCompleter;
import ru.milkyway.plugmanreloaded.download.DownloadService;
import ru.milkyway.plugmanreloaded.commands.CommandOverrideListener;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.managers.HotSwapManager;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.update.UpdateNotifyListener;
import ru.milkyway.plugmanreloaded.update.UpdateService;
import ru.milkyway.plugmanreloaded.update.input.ManualSources;
import ru.milkyway.plugmanreloaded.update.input.ManualSources.ManualSourceListener;
import ru.milkyway.plugmanreloaded.utils.BStats;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;
import ru.milkyway.plugmanreloaded.utils.UpdateChecker;

public final class PlugManReloaded extends JavaPlugin {

    private static PlugManReloaded instance;

    private ConfigManager configManager;
    private LifecycleManager pluginLifecycleManager;
    private ConfirmationManager confirmationManager;
    private HotSwapManager hotSwapManager;
    private UpdateChecker updateChecker;
    private UpdateService updateService;
    private DownloadService downloadService;
    private ManualSources manualSources;
    private CommandHandler commandHandler;
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
            Log.debug("startup.reading-config");
            configManager = new ConfigManager(this);

            Log.debug("startup.detecting-platform", "platform", PlatformDetector.getPlatformName());
            PlatformDetector.flushDiagnostics();

            Log.debug("startup.init-core");
            pluginLifecycleManager = new LifecycleManager(this);
            confirmationManager = new ConfirmationManager(this);
            getServer().getPluginManager().registerEvents(confirmationManager, this);

            Log.debug("startup.registering-commands");
            commandHandler = new CommandHandler(this);
            CommandTabCompleter tabCompleter = new CommandTabCompleter(this, commandHandler);

            registerCommand("plugmanreloaded", commandHandler, tabCompleter);

            Log.debug("startup.init-hotswap");
            hotSwapManager = new HotSwapManager(this, pluginLifecycleManager);

            Log.debug("startup.init-updates");
            updateService = new UpdateService(this);
            getServer().getPluginManager().registerEvents(new UpdateNotifyListener(this, updateService), this);
            updateService.getNotifications().checkOnStartIfEnabled();
            String downloadUserAgent = "PlugManReloaded/" + PluginMetaHelper.getVersion(this);
            downloadService = new DownloadService(this, updateService.getServerProfile(), updateService.getCatalog(), downloadUserAgent);
            manualSources = new ManualSources(this);
            getServer().getPluginManager().registerEvents(new ManualSourceListener(this, manualSources), this);
            updateChecker = new UpdateChecker(this);
            getServer().getPluginManager().registerEvents(updateChecker, this);
            getServer().getPluginManager().registerEvents(new CommandOverrideListener(this), this);

            Log.debug("startup.registering-api");
            api = new PlugManAPIImpl(this);
            PlugManProvider.register(api);
            getServer().getServicesManager().register(PlugManAPI.class, api, this, ServicePriority.Normal);

            return true;
        } catch (Exception | LinkageError t) {
            Log.error("startup.init-failed", t, "error", String.valueOf(t.getMessage()));
            return false;
        }
    }

    private void registerCommand(String name, CommandHandler handler, CommandTabCompleter completer) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(completer);
        }
    }

    private void printBannerHeader() {
        console("&#ffff00 ");
        console("&#ffff00  █▀█ █░░ █░█ █▀▀ █▀▄▀█ ▄▀█ █▄░█ █▀█ █▀▀ █░░ █▀█ ▄▀█ █▀▄ █▀▀ █▀▄");
        console("&#ffff00  █▀▀ █▄▄ █▄█ █▄█ █░▀░█ █▀█ █░▀█ █▀▄ ██▄ █▄▄ █▄█ █▀█ █▄▀ ██▄ █▄▀");
        console("&#ffff00 ");
        console("&f                     (By MilkyWay for everyone)");
        console("&#ffff00 ");
    }

    private void logStartupInfo(long loadTime) {
        printBannerHeader();
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
            Log.debug("shutdown.starting");
        }

        if (hotSwapManager != null) {
            if (initialized) Log.debug("shutdown.stopping-hotswap");
            hotSwapManager.stop();
        }

        if (updateChecker != null) {
            if (initialized) Log.debug("shutdown.stopping-updatechecker");
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
            Log.debug("shutdown.clearing-caches");
        }
        if (getServer() != null && getServer().getServicesManager() != null) {
            getServer().getServicesManager().unregisterAll(this);
        }
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
        printBannerHeader();
        Log.console("shutdown.banner-disabled");
        console("&#ffff00 ");
        Log.console("shutdown.banner-version", "version", PluginMetaHelper.getVersion(this));
        Log.console("shutdown.banner-time", "ms", String.valueOf(unloadTime));
        console("&#ffff00 ");
    }

    public void console(String message) {
        if (message == null) return;
        if (Bukkit.getServer() != null && Bukkit.getConsoleSender() != null) {
            Bukkit.getConsoleSender().sendMessage(HexColors.translateForConsole(message));
        }
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

    public static PlugManReloaded getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LifecycleManager getPluginLifecycleManager() {
        return pluginLifecycleManager;
    }

    public ConfirmationManager getConfirmationManager() {
        return confirmationManager;
    }

    public HotSwapManager getHotSwapManager() {
        return hotSwapManager;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public UpdateService getUpdateService() {
        return updateService;
    }

    public DownloadService getDownloadService() {
        return downloadService;
    }

    public ManualSources getManualSources() {
        return manualSources;
    }

    public CommandHandler getCommandHandler() {
        return commandHandler;
    }

    public CommandHandler getCommandsHandler() {
        return commandHandler;
    }

    public PlugManAPI getApi() {
        return api;
    }

    public boolean isInitialized() {
        return initialized;
    }
}

