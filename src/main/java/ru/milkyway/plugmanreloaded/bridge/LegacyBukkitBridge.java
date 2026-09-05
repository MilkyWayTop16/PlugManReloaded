package ru.milkyway.plugmanreloaded.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.BrigadierManager;
import ru.milkyway.plugmanreloaded.managers.PluginCleanup;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.utils.ErrorAnalyzer;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

public class LegacyBukkitBridge implements PlatformBridge {

    protected final PlugManReloaded plugin;
    protected final BrigadierManager brigadierManager;
    protected final PluginCleanup pluginCleanup;

    public LegacyBukkitBridge(PlugManReloaded plugin, BrigadierManager brigadierManager, PluginCleanup pluginCleanup) {
        this.plugin = plugin;
        this.brigadierManager = brigadierManager;
        this.pluginCleanup = pluginCleanup;
    }

    @Override
    public PluginResult enablePlugin(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) {
            return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND);
        }
        if (targetPlugin.isEnabled()) {
            return PluginResult.ofError(FailureReason.ALREADY_ENABLED, "plugin", targetPlugin.getName());
        }

        try {
            Bukkit.getPluginManager().enablePlugin(targetPlugin);
            brigadierManager.syncCommands();
            if (!targetPlugin.isEnabled()) {
                return PluginResult.ofError(FailureReason.ENABLE_FAILED, "plugin", targetPlugin.getName(), "error", plugin.getConfigManager().text("actions.errors.details.self-disabled"));
            }
            return PluginResult.ofSuccess("enable.success", "plugin", targetPlugin.getName(), "version", PluginMetaHelper.getVersion(targetPlugin));
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage().toLowerCase(Locale.ROOT) : "";
            boolean isZipClosed = msg.contains("zip file") || msg.contains("classloader")
                    || (t.getCause() != null && t.getCause().getMessage() != null && t.getCause().getMessage().toLowerCase(Locale.ROOT).contains("zip file"));
            if (isZipClosed) {
                File file = getPluginFile(targetPlugin);
                if (file != null && file.exists()) {
                    return restartPlugin(targetPlugin);
                }
            }
            Log.warn("legacybukkitbridge.enable-error", t, "plugin", targetPlugin.getName());
            return PluginResult.ofError(FailureReason.ENABLE_FAILED, t, "plugin", targetPlugin.getName(), "error", ErrorAnalyzer.describe(t, plugin));
        }
    }

    @Override
    public PluginResult disablePlugin(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) {
            return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND);
        }
        if (!targetPlugin.isEnabled()) {
            return PluginResult.ofError(FailureReason.ALREADY_DISABLED, "plugin", targetPlugin.getName());
        }

        try {
            Bukkit.getPluginManager().disablePlugin(targetPlugin);
            brigadierManager.unregisterPluginCommands(targetPlugin);
            brigadierManager.syncCommands();

            return PluginResult.ofSuccess("disable.success", "plugin", targetPlugin.getName(), "version", PluginMetaHelper.getVersion(targetPlugin));
        } catch (Throwable t) {
            Log.warn("legacybukkitbridge.disable-error", t, "plugin", targetPlugin.getName());
            return PluginResult.ofError(FailureReason.DISABLE_FAILED, t, "plugin", targetPlugin.getName(), "error", ErrorAnalyzer.describe(t, plugin));
        }
    }

    @Override
    public PluginResult loadPlugin(@Nullable File file) {
        if (file == null || !file.exists()) {
            return PluginResult.ofError(FailureReason.INVALID_PLUGIN);
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(file, null, false);
        if (!report.isValid()) {
            return report.toFailure(file);
        }

        try {
            PluginJarIndex.JarDescriptor desc = plugin.getPluginLifecycleManager().getJarIndex().readDescriptor(file);
            if (desc != null && desc.declaredName() != null) {
                Plugin already = Bukkit.getPluginManager().getPlugin(desc.declaredName());
                if (already != null) {
                    File sourceFile = plugin.getPluginLifecycleManager().getPluginFile(already);
                    String sourceName = sourceFile != null ? sourceFile.getName() : plugin.getConfigManager().text("actions.errors.details.loaded-from-memory");
                    return PluginResult.ofError(FailureReason.DUPLICATE_PLUGIN, "plugin", desc.declaredName(), "file", file.getName(), "source", sourceName);
                }
            }

            Plugin loaded = Bukkit.getPluginManager().loadPlugin(file);
            if (loaded == null) {
                return PluginResult.ofError(FailureReason.LOAD_FAILED, "plugin", file.getName(), "file", file.getName(), "error", plugin.getConfigManager().text("actions.errors.details.empty-load-result"));
            }

            if (!PlatformDetector.isModernPaper()) {
                loaded.onLoad();
            }

            Bukkit.getPluginManager().enablePlugin(loaded);
            brigadierManager.syncCommands();

            if (!loaded.isEnabled()) {
                unloadPlugin(loaded);
                return PluginResult.ofError(FailureReason.ENABLE_FAILED, "plugin", loaded.getName(), "file", file.getName(), "error", plugin.getConfigManager().text("actions.errors.details.self-disabled-on-enable"));
            }

            return PluginResult.ofSuccess("load.success", "plugin", loaded.getName(), "version", PluginMetaHelper.getVersion(loaded));
        } catch (Throwable t) {
            Log.warn("legacybukkitbridge.load-error", t, "file", file.getName());
            ErrorAnalyzer.ErrorDetails details = ErrorAnalyzer.analyze(t, file.getName(), file.getName());
            return PluginResult.ofError(details.reason(), t, details.placeholders());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PluginResult unloadPlugin(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return PluginResult.ofError(FailureReason.INVALID_PLUGIN);
        String name = targetPlugin.getName();

        try {

            if (targetPlugin.isEnabled()) {
                Bukkit.getPluginManager().disablePlugin(targetPlugin);
            }

            brigadierManager.unregisterPluginCommands(targetPlugin);

            pluginCleanup.cleanup(targetPlugin);

            PluginManager pm = Bukkit.getPluginManager();
            if (pm instanceof SimplePluginManager spm) {
                List<Plugin> plugins = ReflectionHelper.getFieldValue(SimplePluginManager.class, spm, "plugins");
                if (plugins != null) {
                    plugins.remove(targetPlugin);
                }

                Map<String, Plugin> lookupNames = ReflectionHelper.getFieldValue(SimplePluginManager.class, spm, "lookupNames");
                if (lookupNames != null) {
                    lookupNames.remove(name.toLowerCase(Locale.ROOT));
                    lookupNames.remove(name);
                }
            }

            postUnloadCleanup(targetPlugin);

            brigadierManager.syncCommands();

            return PluginResult.ofSuccess("unload.success", "plugin", name, "version", PluginMetaHelper.getVersion(targetPlugin));
        } catch (Exception | LinkageError t) {
            Log.warn("legacybukkitbridge.unload-error", t, "plugin", name);
            return PluginResult.ofError(FailureReason.UNLOAD_FAILED, t, "plugin", name, "error", ErrorAnalyzer.describe(t, plugin));
        }
    }

    protected void postUnloadCleanup(Plugin targetPlugin) {
    }

    @Override
    public PluginResult reloadPlugin(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return PluginResult.ofError(FailureReason.INVALID_PLUGIN);

        PluginResult disableResult = disablePlugin(targetPlugin);
        if (!disableResult.success()) return disableResult;

        PluginResult enableResult = enablePlugin(targetPlugin);
        if (!enableResult.success()) return enableResult;

        return PluginResult.ofSuccess("reload.success", "plugin", targetPlugin.getName(), "version", PluginMetaHelper.getVersion(targetPlugin));
    }

    @Override
    public PluginResult restartPlugin(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return PluginResult.ofError(FailureReason.INVALID_PLUGIN);

        File file = getPluginFile(targetPlugin);
        if (file == null || !file.exists()) {
            String errorMsg = plugin.getConfigManager().text("actions.errors.details.file-not-found",
                    "file", targetPlugin.getName(), "plugin", targetPlugin.getName());
            return PluginResult.ofError(FailureReason.LOAD_FAILED, "plugin", targetPlugin.getName(), "file", targetPlugin.getName(), "error", errorMsg);
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(file, targetPlugin.getName(), true);
        if (!report.isValid()) {
            return PluginResult.ofError(FailureReason.LOAD_FAILED, "plugin", targetPlugin.getName(), "file", file.getName(), "error", report.errorMessage());
        }

        String name = targetPlugin.getName();
        PluginResult unloadResult = unloadPlugin(targetPlugin);
        if (!unloadResult.success()) return unloadResult;

        PluginResult loadResult = loadPlugin(file);
        if (!loadResult.success()) {
            loadResult = loadPlugin(file);
        }
        if (!loadResult.success()) {
            String errorDetail = loadResult.detail(plugin.getConfigManager().text("actions.errors.details.unknown"));
            return PluginResult.ofError(FailureReason.RESTART_LEFT_UNLOADED, loadResult.error(), "plugin", name, "error", errorDetail);
        }

        return PluginResult.ofSuccess("restart.success", "plugin", name, "version", loadResult.placeholders().getOrDefault("version", ""));
    }

    @Override
    public @Nullable File getPluginFile(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return null;

        File pluginsDir = plugin.getDataFolder().getParentFile();

        if (targetPlugin instanceof JavaPlugin jp) {
            File f = ReflectionHelper.getFieldValue(JavaPlugin.class, targetPlugin, "file");
            if (f != null && f.exists()) {
                File resolved = resolveOriginalJar(f, pluginsDir);
                if (resolved != null && resolved.exists()) return resolved;
            }

            try {
                File mf = ReflectionHelper.invokeMethod(jp, "getFile");
                if (mf != null && mf.exists()) {
                    File resolved = resolveOriginalJar(mf, pluginsDir);
                    if (resolved != null && resolved.exists()) return resolved;
                }
            } catch (Throwable ignored) {}
        }

        try {
            CodeSource cs = targetPlugin.getClass().getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.exists() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    File resolved = resolveOriginalJar(f, pluginsDir);
                    if (resolved != null && resolved.exists()) return resolved;
                }
            }
        } catch (Throwable ignored) {}

        try {
            ClassLoader cl = targetPlugin.getClass().getClassLoader();
            if (cl instanceof URLClassLoader ucl) {
                URL[] urls = ucl.getURLs();
                if (urls != null && urls.length > 0) {
                    File f = new File(urls[0].toURI());
                    if (f.exists() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        File resolved = resolveOriginalJar(f, pluginsDir);
                        if (resolved != null && resolved.exists()) return resolved;
                    }
                }
            }
        } catch (Throwable ignored) {}

        File fromIndex = plugin.getPluginLifecycleManager().getJarIndex().find(targetPlugin.getName());
        if (fromIndex != null && fromIndex.exists() && isDirectChildOf(fromIndex, pluginsDir)) {
            return fromIndex;
        }

        return fromIndex;
    }

    private final Map<File, File> resolvedJarCache = new ConcurrentHashMap<>();

    private @Nullable File resolveOriginalJar(@Nullable File file, File pluginsDir) {
        if (file == null) return null;
        File cached = resolvedJarCache.get(file);
        if (cached != null && cached.exists()) {
            return cached;
        }

        if (isDirectChildOf(file, pluginsDir)) {
            resolvedJarCache.put(file, file);
            return file;
        }

        File inPlugins = new File(pluginsDir, file.getName());
        if (inPlugins.exists()) {
            resolvedJarCache.put(file, inPlugins);
            return inPlugins;
        }

        String unmappedName = file.getName().replaceFirst("-\\d{10,20}\\.jar$", ".jar");
        File unmappedFile = new File(pluginsDir, unmappedName);
        if (unmappedFile.exists()) {
            resolvedJarCache.put(file, unmappedFile);
            return unmappedFile;
        }

        return null;
    }

    private boolean isDirectChildOf(@Nullable File file, File parentDir) {
        if (file == null || parentDir == null) return false;
        File parent = file.getParentFile();
        return parent != null && parent.equals(parentDir);
    }

    @Override
    public void syncCommands() {
        brigadierManager.syncCommands();
    }

    @Override
    public boolean isPaperPlugin(@Nullable File file) {
        if (file == null || !file.exists()) return false;
        try (JarFile jar = new JarFile(file)) {
            return jar.getJarEntry("paper-plugin.yml") != null;
        } catch (Throwable t) {
            Log.warn("legacybukkitbridge.paperplugin-check-error", t, "file", file.getName());
            return false;
        }
    }
}

