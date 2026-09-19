package ru.milkyway.plugmanreloaded.download;

import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.download.DownloadModels.DownloadResult;
import ru.milkyway.plugmanreloaded.download.DownloadModels.DownloadStatus;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

final class DownloadTransaction {

    private final PlugManReloaded plugin;
    private final BiConsumer<PluginDownloader.StagedItem, File> activeCatalogWriter;
    private final BiConsumer<PluginDownloader.StagedItem, File> pendingCatalogWriter;
    private final Runnable refresh;
    private final List<FileChange> fileChanges = new ArrayList<>();
    private final List<Plugin> loaded = new ArrayList<>();
    private final List<PluginState> replaced = new ArrayList<>();
    private final List<Plugin> enabled = new ArrayList<>();

    DownloadTransaction(PlugManReloaded plugin, BiConsumer<PluginDownloader.StagedItem, File> activeCatalogWriter,
                        BiConsumer<PluginDownloader.StagedItem, File> pendingCatalogWriter, Runnable refresh) {
        this.plugin = plugin;
        this.activeCatalogWriter = activeCatalogWriter;
        this.pendingCatalogWriter = pendingCatalogWriter;
        this.refresh = refresh;
    }

    DownloadResult commit(List<PluginDownloader.StagedItem> stagedItems, List<String> existingDisabledToEnable,
                          List<String> existingUnloadedToLoad) {
        if (stagedItems.isEmpty()) {
            return DownloadResult.success("", "", "", List.of());
        }
        PluginDownloader.StageFailure validation = PluginDownloader.validateStagedItems(
                stagedItems.get(0).stagedPath().getParent(), stagedItems);
        if (validation != null) {
            PluginDownloader.StagedItem target = stagedItems.get(stagedItems.size() - 1);
            return DownloadResult.failed(validation.outcome(), target.declaredName(), target.sourceId(), validation.detail());
        }

        try {
            if (requiresRestart(stagedItems)) {
                return stageForRestart(stagedItems);
            }
            if (!activateExisting(existingUnloadedToLoad, existingDisabledToEnable)) {
                return failed(stagedItems.get(stagedItems.size() - 1), "actions.download.details.load-failed-dirty");
            }
            for (PluginDownloader.StagedItem item : stagedItems) {
                if (!installNow(item)) {
                    return failed(item, "actions.download.details.load-failed-dirty");
                }
            }
            verify(stagedItems);
            for (PluginDownloader.StagedItem item : stagedItems) {
                activeCatalogWriter.accept(item, resolveTargetFile(item, plugin.getDataFolder().getParentFile()));
            }
            refresh.run();
            PluginDownloader.StagedItem target = stagedItems.get(stagedItems.size() - 1);
            return DownloadResult.success(target.declaredName(), target.version(), target.sourceId(), installedNames(stagedItems));
        } catch (IOException | LinkageError e) {
            return failed(stagedItems.get(stagedItems.size() - 1), "actions.download.details.disk-write-failed");
        }
    }

    private boolean requiresRestart(List<PluginDownloader.StagedItem> items) {
        for (PluginDownloader.StagedItem item : items) {
            if (item.requiresRestart()) {
                return true;
            }
        }
        return false;
    }

    private DownloadResult stageForRestart(List<PluginDownloader.StagedItem> items) throws IOException {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        Path updateDir = new File(pluginsDir, "update").toPath();
        Files.createDirectories(updateDir);
        for (PluginDownloader.StagedItem item : items) {
            File target = resolveTargetFile(item, pluginsDir);
            Path pending = updateDir.resolve(target.getName());
            Path backup = PluginDownloader.backupForRollback(pending, item.stagedPath().getParent());
            boolean created = backup == null;
            PluginDownloader.moveReplacing(item.stagedPath(), pending);
            fileChanges.add(new FileChange(pending, backup, created));
        }
        verifyPending(items, updateDir, pluginsDir);
        for (PluginDownloader.StagedItem item : items) {
            pendingCatalogWriter.accept(item, resolveTargetFile(item, pluginsDir));
        }
        refresh.run();
        PluginDownloader.StagedItem target = items.get(items.size() - 1);
        return DownloadResult.bootstrapper(target.declaredName(), target.version(), target.sourceId());
    }

    private boolean activateExisting(List<String> unloadedNames, List<String> disabledNames) {
        for (String name : unloadedNames) {
            File file = plugin.getPluginLifecycleManager().getJarIndex().find(name);
            if (file == null || !plugin.getPluginLifecycleManager().load(file).success()) {
                rollback();
                return false;
            }
            Plugin loadedPlugin = plugin.getPluginLifecycleManager().getPlugin(name);
            if (loadedPlugin != null) {
                loaded.add(loadedPlugin);
            }
        }
        for (String name : disabledNames) {
            Plugin pluginInstance = plugin.getPluginLifecycleManager().getPlugin(name);
            if (pluginInstance == null || !plugin.getPluginLifecycleManager().enable(pluginInstance).success()) {
                rollback();
                return false;
            }
            enabled.add(pluginInstance);
        }
        return true;
    }

    private boolean installNow(PluginDownloader.StagedItem item) throws IOException {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File target = resolveTargetFile(item, pluginsDir);
        plugin.getHotSwapManager().temporarilyIgnore(target.getName(), 5000L);
        Plugin running = plugin.getPluginLifecycleManager().getPlugin(item.declaredName());
        boolean wasEnabled = running != null && running.isEnabled();
        if (running != null && !plugin.getPluginLifecycleManager().unload(running, false).success()) {
            rollback();
            return false;
        }
        if (running != null) {
            replaced.add(new PluginState(running.getName(), target, wasEnabled));
        }
        Path backup = PluginDownloader.backupForRollback(target.toPath(), item.stagedPath().getParent());
        boolean created = backup == null;
        PluginDownloader.moveReplacing(item.stagedPath(), target.toPath());
        fileChanges.add(new FileChange(target.toPath(), backup, created));
        PluginResult loadResult = plugin.getPluginLifecycleManager().load(target);
        if (!loadResult.success()) {
            rollback();
            return false;
        }
        Plugin loadedPlugin = plugin.getPluginLifecycleManager().getPlugin(item.declaredName());
        if (loadedPlugin != null) {
            loaded.add(loadedPlugin);
        }
        return true;
    }

    private DownloadResult failed(PluginDownloader.StagedItem item, String detail) {
        boolean clean = rollback();
        return DownloadResult.failed(clean ? DownloadStatus.ROLLED_BACK : DownloadStatus.ACTIVATION_FAILED,
                item.declaredName(), item.sourceId(), clean ? "actions.download.details.load-failed-rolled-back" : detail);
    }

    private boolean rollback() {
        boolean clean = true;
        List<Plugin> reverseLoaded = new ArrayList<>(loaded);
        Collections.reverse(reverseLoaded);
        for (Plugin loadedPlugin : reverseLoaded) {
            if (!plugin.getPluginLifecycleManager().unload(loadedPlugin, false).success()) {
                clean = false;
            }
        }
        List<FileChange> reverseFiles = new ArrayList<>(fileChanges);
        Collections.reverse(reverseFiles);
        for (FileChange change : reverseFiles) {
            clean &= PluginDownloader.restoreFileChange(change.target(), change.backup(), change.created());
        }
        List<PluginState> reverseReplaced = new ArrayList<>(replaced);
        Collections.reverse(reverseReplaced);
        for (PluginState previous : reverseReplaced) {
            if (!plugin.getPluginLifecycleManager().load(previous.file()).success()) {
                clean = false;
                continue;
            }
            Plugin restored = plugin.getPluginLifecycleManager().getPlugin(previous.name());
            if (!previous.wasEnabled() && restored != null && restored.isEnabled()
                    && !plugin.getPluginLifecycleManager().disable(restored).success()) {
                clean = false;
            }
        }
        List<Plugin> reverseEnabled = new ArrayList<>(enabled);
        Collections.reverse(reverseEnabled);
        for (Plugin enabledPlugin : reverseEnabled) {
            if (enabledPlugin.isEnabled() && !plugin.getPluginLifecycleManager().disable(enabledPlugin).success()) {
                clean = false;
            }
        }
        return clean;
    }

    private void verify(List<PluginDownloader.StagedItem> items) throws IOException {
        for (PluginDownloader.StagedItem item : items) {
            File target = resolveTargetFile(item, plugin.getDataFolder().getParentFile());
            if (!Files.isRegularFile(target.toPath()) || !PluginDownloader.matchesArtifact(target.toPath(), item)) {
                throw new IOException("installed artifact validation failed");
            }
        }
    }

    private void verifyPending(List<PluginDownloader.StagedItem> items, Path updateDir, File pluginsDir) throws IOException {
        for (PluginDownloader.StagedItem item : items) {
            Path pending = updateDir.resolve(resolveTargetFile(item, pluginsDir).getName());
            if (!Files.isRegularFile(pending) || !PluginDownloader.matchesArtifact(pending, item)) {
                throw new IOException("pending artifact validation failed");
            }
        }
    }

    private File resolveTargetFile(PluginDownloader.StagedItem item, File pluginsDir) {
        if (Bukkit.getServer() == null || Bukkit.getScheduler() == null) {
            return new File(pluginsDir, item.declaredName() + ".jar");
        }
        File existing = plugin.getPluginLifecycleManager().getJarIndex().find(item.declaredName());
        if (existing != null && existing.exists()) {
            PluginJarIndex.JarDescriptor descriptor = plugin.getPluginLifecycleManager().getJarIndex().readDescriptor(existing);
            if (descriptor != null && item.declaredName().equalsIgnoreCase(descriptor.declaredName())) {
                return existing;
            }
        }
        return new File(pluginsDir, item.declaredName() + ".jar");
    }

    private List<String> installedNames(List<PluginDownloader.StagedItem> items) {
        List<String> names = new ArrayList<>(items.size());
        for (PluginDownloader.StagedItem item : items) {
            names.add(item.declaredName());
        }
        return names;
    }

    private record FileChange(Path target, @Nullable Path backup, boolean created) {}

    private record PluginState(String name, File file, boolean wasEnabled) {}
}
