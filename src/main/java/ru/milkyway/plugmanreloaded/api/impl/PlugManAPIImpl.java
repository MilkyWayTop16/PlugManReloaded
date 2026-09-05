package ru.milkyway.plugmanreloaded.api.impl;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.*;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.managers.DependencyGraph;
import ru.milkyway.plugmanreloaded.managers.HotSwapManager;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.update.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateService;
import ru.milkyway.plugmanreloaded.update.install.BackupStore;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import ru.milkyway.plugmanreloaded.utils.Log;

public final class PlugManAPIImpl implements PlugManAPI, PluginLifecycleService, DependencyGraphService, PluginRegistryService, UpdateServiceAPI, HotSwapService {
    private final PlugManReloaded plugin;
    private final LifecycleManager lifecycleManager;
    private final DependencyGraph graphManager;
    private final PluginJarIndex jarIndex;
    private final UpdateService updateService;
    private final HotSwapManager hotSwapManager;

    public PlugManAPIImpl(PlugManReloaded plugin) {
        this.plugin = plugin;
        this.lifecycleManager = plugin.getPluginLifecycleManager();
        this.graphManager = lifecycleManager.getDependencyGraph();
        this.jarIndex = lifecycleManager.getJarIndex();
        this.updateService = plugin.getUpdateService();
        this.hotSwapManager = plugin.getHotSwapManager();
    }

    private PluginResult runSyncIfNeeded(Supplier<PluginResult> action) {
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            return action.get();
        }
        CompletableFuture<PluginResult> future = new CompletableFuture<>();
        TaskScheduler.runSync(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.complete(PluginResult.ofError(FailureReason.INTERNAL_ERROR, t));
            }
        });
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return PluginResult.ofError(FailureReason.TIMEOUT, t);
        }
    }

    @Override
    public PluginLifecycleService lifecycle() {
        return this;
    }

    @Override
    public DependencyGraphService graph() {
        return this;
    }

    @Override
    public PluginRegistryService registry() {
        return this;
    }

    @Override
    public UpdateServiceAPI updates() {
        return this;
    }

    @Override
    public HotSwapService hotswap() {
        return this;
    }

    @Override
    public PluginResult loadPlugin(File file) {
        return runSyncIfNeeded(() -> lifecycleManager.load(file));
    }

    @Override
    public PluginResult loadPlugin(String pluginName) {
        return runSyncIfNeeded(() -> {
            File file = jarIndex.find(pluginName);
            if (file == null) {
                File pluginsDir = plugin.getDataFolder().getParentFile();
                file = new File(pluginsDir, pluginName.endsWith(".jar") ? pluginName : pluginName + ".jar");
            }
            return lifecycleManager.load(file);
        });
    }

    @Override
    public PluginResult unloadPlugin(Plugin targetPlugin, boolean deep) {
        return runSyncIfNeeded(() -> lifecycleManager.unload(targetPlugin, deep));
    }

    @Override
    public PluginResult unloadPlugin(String pluginName, boolean deep) {
        return runSyncIfNeeded(() -> {
            Plugin target = lifecycleManager.getPlugin(pluginName);
            if (target == null) {
                return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND, "plugin", pluginName);
            }
            return lifecycleManager.unload(target, deep);
        });
    }

    @Override
    public PluginResult reloadPlugin(Plugin targetPlugin) {
        return runSyncIfNeeded(() -> lifecycleManager.reload(targetPlugin));
    }

    @Override
    public PluginResult reloadPlugin(String pluginName) {
        return runSyncIfNeeded(() -> {
            Plugin target = lifecycleManager.getPlugin(pluginName);
            if (target == null) {
                return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND, "plugin", pluginName);
            }
            return lifecycleManager.reload(target);
        });
    }

    @Override
    public PluginResult restartPlugin(Plugin targetPlugin) {
        return runSyncIfNeeded(() -> lifecycleManager.restart(targetPlugin));
    }

    @Override
    public PluginResult restartPlugin(String pluginName) {
        return runSyncIfNeeded(() -> {
            Plugin target = lifecycleManager.getPlugin(pluginName);
            if (target == null) {
                return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND, "plugin", pluginName);
            }
            return lifecycleManager.restart(target);
        });
    }

    @Override
    public PluginResult enablePlugin(Plugin targetPlugin) {
        return runSyncIfNeeded(() -> lifecycleManager.enable(targetPlugin));
    }

    @Override
    public PluginResult enablePlugin(String pluginName) {
        return runSyncIfNeeded(() -> {
            Plugin target = lifecycleManager.getPlugin(pluginName);
            if (target == null) {
                return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND, "plugin", pluginName);
            }
            return lifecycleManager.enable(target);
        });
    }

    @Override
    public PluginResult disablePlugin(Plugin targetPlugin) {
        return runSyncIfNeeded(() -> lifecycleManager.disable(targetPlugin));
    }

    @Override
    public PluginResult disablePlugin(String pluginName) {
        return runSyncIfNeeded(() -> {
            Plugin target = lifecycleManager.getPlugin(pluginName);
            if (target == null) {
                return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND, "plugin", pluginName);
            }
            return lifecycleManager.disable(target);
        });
    }

    private void backupBeforeDelete(String pluginName, String version, File jar) {
        try {
            File pluginsDir = plugin.getDataFolder().getParentFile();
            BackupStore backups = new BackupStore(
                    pluginsDir,
                    plugin.getConfigManager().getBackupKeepDays(),
                    plugin.getConfigManager().getBackupMaxPerPlugin()
            );
            backups.backup(pluginName, version != null ? version : "1.0", jar);
        } catch (Throwable t) {
            Log.warn("plugmanapiimpl.backup-before-delete-failed", t, "plugin", pluginName);
        }
    }

    @Override
    public PluginResult deletePlugin(@Nullable Plugin targetPlugin) {
        return runSyncIfNeeded(() -> {
            if (targetPlugin == null) {
                return PluginResult.ofError(FailureReason.PLUGIN_NOT_FOUND, "plugin", "null");
            }
            File file = lifecycleManager.getPluginFile(targetPlugin);
            if (file == null || !file.exists()) {
                return PluginResult.ofError(FailureReason.FILE_NOT_FOUND, "plugin", targetPlugin.getName());
            }
            backupBeforeDelete(targetPlugin.getName(), PluginMetaHelper.getVersion(targetPlugin), file);
            PluginResult unloadRes = lifecycleManager.unload(targetPlugin);
            if (!unloadRes.success()) {
                return unloadRes;
            }
            try {
                Files.deleteIfExists(file.toPath());
                jarIndex.invalidate();
                return PluginResult.ofSuccess("delete.success", "plugin", targetPlugin.getName(), "file", file.getName());
            } catch (Exception e) {
                return PluginResult.ofError(FailureReason.DELETE_FAILED, e, "plugin", targetPlugin.getName(), "file", file.getName());
            }
        });
    }

    @Override
    public PluginResult deletePlugin(String pluginName) {
        return runSyncIfNeeded(() -> {
            Plugin target = lifecycleManager.getPlugin(pluginName);
            if (target != null) {
                return deletePlugin(target);
            }
            File file = jarIndex.find(pluginName);
            if (file == null || !file.exists()) {
                return PluginResult.ofError(FailureReason.FILE_NOT_FOUND, "plugin", pluginName);
            }
            PluginJarIndex.JarDescriptor desc = jarIndex.readDescriptor(file);
            backupBeforeDelete(pluginName, desc != null ? desc.version() : null, file);
            try {
                Files.deleteIfExists(file.toPath());
                jarIndex.invalidate();
                return PluginResult.ofSuccess("delete.success", "plugin", pluginName, "file", file.getName());
            } catch (Exception e) {
                return PluginResult.ofError(FailureReason.DELETE_FAILED, e, "plugin", pluginName, "file", file.getName());
            }
        });
    }

    @Override
    public boolean isLoaded(String pluginName) {
        return lifecycleManager.getPlugin(pluginName) != null;
    }

    @Override
    public boolean isEnabled(String pluginName) {
        Plugin target = lifecycleManager.getPlugin(pluginName);
        return target != null && target.isEnabled();
    }

    @Override
    public boolean isProtected(String pluginName) {
        Plugin target = lifecycleManager.getPlugin(pluginName);
        return target != null && lifecycleManager.isProtected(target);
    }

    @Override
    public DependencyNode getNode(String pluginName) {
        Map<String, DependencyNode> graph = graphManager.buildGraph(true);
        return graph.get(pluginName.toLowerCase(Locale.ROOT));
    }

    @Override
    public Set<String> getDependents(String pluginName) {
        return graphManager.getDependents(pluginName);
    }

    @Override
    public Set<String> getDependencies(String pluginName) {
        DependencyNode node = getNode(pluginName);
        if (node == null) return Collections.emptySet();
        Set<String> all = new HashSet<>(node.getHardDependencies());
        all.addAll(node.getSoftDependencies());
        return all;
    }

    @Override
    public List<String> getCascadeUnloadOrder(String pluginName) {
        return graphManager.calculateCascadeOrder(pluginName);
    }

    @Override
    public List<String> getCascadeLoadOrder(String pluginName) {
        List<String> order = new ArrayList<>(graphManager.calculateCascadeOrder(pluginName));
        Collections.reverse(order);
        return order;
    }

    @Override
    public Map<String, DependencyNode> getFullGraph() {
        return graphManager.buildGraph(true);
    }

    @Override
    public Optional<PluginInfo> getPluginInfo(String pluginName) {
        Plugin target = lifecycleManager.getPlugin(pluginName);
        if (target != null) {
            return Optional.ofNullable(lifecycleManager.getPluginInfo(target));
        }
        File jar = jarIndex.find(pluginName);
        if (jar != null && jar.exists()) {
            return Optional.ofNullable(PluginInfo.fromJarFile(jar));
        }
        return Optional.empty();
    }

    @Override
    public Optional<PluginInfo> getPluginInfo(Plugin plugin) {
        return Optional.ofNullable(lifecycleManager.getPluginInfo(plugin));
    }

    @Override
    public Optional<File> findJarFile(String pluginName) {
        return Optional.ofNullable(jarIndex.find(pluginName));
    }

    @Override
    public List<String> getLoadablePluginNames() {
        return jarIndex.loadableNames();
    }

    @Override
    public List<PluginInfo> getAllPlugins() {
        List<PluginInfo> result = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            PluginInfo info = lifecycleManager.getPluginInfo(p);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<Optional<UpdateInfo>> checkUpdate(@Nullable Plugin targetPlugin) {
        CompletableFuture<Optional<UpdateInfo>> future = new CompletableFuture<>();
        if (targetPlugin == null) {
            future.complete(Optional.empty());
            return future;
        }
        updateService.checkOne(targetPlugin, candidates -> {
            if (candidates == null || candidates.isEmpty()) {
                future.complete(Optional.empty());
            } else {
                future.complete(Optional.of(UpdateInfo.from(candidates.get(0))));
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Optional<UpdateInfo>> checkUpdate(String pluginName) {
        Plugin target = lifecycleManager.getPlugin(pluginName);
        if (target != null) {
            return checkUpdate(target);
        }
        CompletableFuture<Optional<UpdateInfo>> future = new CompletableFuture<>();
        future.complete(Optional.empty());
        return future;
    }

    @Override
    public CompletableFuture<List<UpdateInfo>> checkAllUpdates() {
        CompletableFuture<List<UpdateInfo>> future = new CompletableFuture<>();
        updateService.checkAll(candidates -> {
            if (candidates == null || candidates.isEmpty()) {
                future.complete(Collections.emptyList());
            } else {
                List<UpdateInfo> list = new ArrayList<>();
                for (UpdateCandidate c : candidates) {
                    list.add(UpdateInfo.from(c));
                }
                future.complete(list);
            }
        });
        return future;
    }

    @Override
    public boolean isEnabled() {
        return hotSwapManager != null && hotSwapManager.isRunning();
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (hotSwapManager == null) return;
        if (enabled) {
            hotSwapManager.start();
        } else {
            hotSwapManager.stop();
        }
    }
}

