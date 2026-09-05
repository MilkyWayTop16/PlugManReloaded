package ru.milkyway.plugmanreloaded.update.install;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.UnloadSafetyChecker;
import ru.milkyway.plugmanreloaded.managers.ServerStateCleanup;
import ru.milkyway.plugmanreloaded.update.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateCandidate;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.MetaspaceCleanup;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class UpdateInstaller {

    private final PlugManReloaded plugin;
    private final BackupStore backups;
    private final String userAgent;

    public UpdateInstaller(PlugManReloaded plugin, String userAgent) {
        this.plugin = plugin;
        this.userAgent = userAgent;
        this.backups = new BackupStore(plugin.getDataFolder().getParentFile(),
                plugin.getConfigManager().getBackupKeepDays(),
                plugin.getConfigManager().getBackupMaxPerPlugin());
        TaskScheduler.runAsync(plugin, this.backups::pruneAll);
    }

    private record Preparation(InstallResult error, Path jarBackup, Path folderBackup, Path staged) {}

    public void install(UpdateCandidate candidate, Consumer<InstallResult> callback) {
        install(candidate, true, callback);
    }

    public void install(UpdateCandidate candidate, boolean restartDependents, Consumer<InstallResult> callback) {
        PluginIdentity identity = candidate.identity();
        RemoteVersion version = candidate.version();

        if (version == null || !version.downloadable()) {
            TaskScheduler.runSync(plugin, () -> callback.accept(
                    InstallResult.failed(InstallStatus.NOT_INSTALLABLE, identity.pluginName(), "actions.update.details.no-download-link")));
            return;
        }

        if (!plugin.getDownloadService().getLockManager().tryLock(identity.pluginName())) {
            TaskScheduler.runSync(plugin, () -> callback.accept(
                    InstallResult.failed(InstallStatus.NOT_INSTALLABLE, identity.pluginName(), "actions.update.details.locked")));
            return;
        }

        Consumer<InstallResult> wrappedCallback = res -> {
            plugin.getDownloadService().getLockManager().unlock(identity.pluginName());
            callback.accept(res);
        };

        TaskScheduler.runAsync(plugin, () -> {
            Preparation prep = prepare(identity, version);
            if (prep.error() != null) {
                TaskScheduler.runSync(plugin, () -> wrappedCallback.accept(prep.error()));
                return;
            }
            TaskScheduler.runSync(plugin, () -> wrappedCallback.accept(swap(identity, version, prep.staged(), prep.jarBackup(), prep.folderBackup(), restartDependents)));
        });
    }

    private Path stagedPath(PluginIdentity identity, RemoteVersion version) {
        String fileName = version.fileName() != null && !version.fileName().isBlank()
                ? version.fileName()
                : identity.pluginName() + "-" + version.versionNumber() + ".jar";
        String txId = UUID.randomUUID().toString().substring(0, 8);
        return plugin.getDataFolder().getParentFile().toPath()
                .resolve(".plugmanreloaded-tmp")
                .resolve("up_" + txId)
                .resolve(fileName.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private Preparation prepare(PluginIdentity identity, RemoteVersion version) {
        Path staged = stagedPath(identity, version);

        DownloadClient.Downloaded downloaded = DownloadClient.download(version.downloadUrl(), staged, userAgent);
        if (downloaded == null) {
            return new Preparation(InstallResult.failed(InstallStatus.DOWNLOAD_FAILED, identity.pluginName(), "actions.update.details.download-failed"), null, null, null);
        }

        String hashProblem = verifyHash(version, downloaded);
        if (hashProblem != null) {
            deleteQuietly(staged);
            return new Preparation(InstallResult.failed(InstallStatus.HASH_MISMATCH, identity.pluginName(), hashProblem), null, null, null);
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(staged.toFile(), identity.pluginName(), false);
        if (!report.isValid()) {
            deleteQuietly(staged);
            InstallStatus outcome = switch (report.status()) {
                case INCOMPATIBLE_JAVA -> InstallStatus.NOT_INSTALLABLE;
                case NAME_MISMATCH, NO_DESCRIPTOR -> InstallStatus.WRONG_PLUGIN;
                case MISSING_DEPENDENCIES -> InstallStatus.MISSING_DEPENDENCY;
                default -> InstallStatus.NOT_INSTALLABLE;
            };
            return new Preparation(InstallResult.failed(outcome, identity.pluginName(), report.errorMessage()), null, null, null);
        }

        Path jarBackup = backups.backup(identity.pluginName(), identity.currentVersion(), identity.jarFile());
        if (jarBackup == null) {
            deleteQuietly(staged);
            return new Preparation(InstallResult.failed(InstallStatus.NOT_INSTALLABLE, identity.pluginName(),
                    "actions.update.details.backup-failed"), null, null, null);
        }

        Plugin loadedPlugin = plugin.getPluginLifecycleManager().getPlugin(identity.pluginName());
        Path folderBackup = (loadedPlugin != null && loadedPlugin.getDataFolder().exists())
                ? backups.backupFolder(identity.pluginName(), loadedPlugin.getDataFolder())
                : null;

        return new Preparation(null, jarBackup, folderBackup, staged);
    }

    private @Nullable String verifyHash(RemoteVersion version, DownloadClient.Downloaded downloaded) {
        String expectedSha1 = version.expectedSha1();
        if (expectedSha1 != null && !expectedSha1.isBlank()
                && !expectedSha1.toLowerCase(Locale.ROOT).equals(downloaded.sha1())) {
            return "actions.update.details.sha1-mismatch";
        }

        String expectedSha256 = version.expectedSha256();
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.toLowerCase(Locale.ROOT).equals(downloaded.sha256())) {
            return "actions.update.details.sha256-mismatch";
        }
        return null;
    }

    private InstallResult swap(PluginIdentity identity, RemoteVersion version, Path staged, Path jarBackup, Path folderBackup, boolean restartDependents) {
        File oldTarget = identity.jarFile();
        String from = identity.currentVersion();
        String to = version.versionNumber();

        File target = determineTargetFile(oldTarget, version, identity, plugin.getDataFolder().getParentFile());

        Plugin loaded = plugin.getPluginLifecycleManager().getPlugin(identity.pluginName());
        boolean isUnsafe = loaded != null && (
                plugin.getPluginLifecycleManager().getSafetyAdvisor().assess(loaded).riskLevel() == UnloadSafetyChecker.PluginRiskLevel.UNLOADABLE_HOSTILE
                || plugin.getPluginLifecycleManager().getSafetyAdvisor().assess(loaded).riskLevel() == UnloadSafetyChecker.PluginRiskLevel.CRITICAL_PROTECTED
                || plugin.getConfigManager().isUnsafeToUnload(identity.pluginName())
        );

        if (isUnsafe) {
            return stageForRestart(identity, version, staged, oldTarget, from, to);
        }

        ServerStateCleanup.closeAllOnlineInventories();

        List<DependentInfo> dependents = restartDependents ? collectDependentInfos(identity.pluginName()) : List.of();

        if (restartDependents && !dependents.isEmpty()) {
            List<DependentInfo> unloadOrder = new ArrayList<>(dependents);
            Collections.reverse(unloadOrder);
            for (DependentInfo dep : unloadOrder) {
                Plugin depPlugin = plugin.getPluginLifecycleManager().getPlugin(dep.name());
                if (depPlugin != null) {
                    plugin.getPluginLifecycleManager().unload(depPlugin);
                }
            }
        }

        if (loaded != null) {
            PluginResult unloadResult = plugin.getPluginLifecycleManager().unload(loaded);
            if (!unloadResult.success()) {
                if (restartDependents && !dependents.isEmpty()) {
                    loadDependents(identity.pluginName(), dependents);
                }
                return stageForRestart(identity, version, staged, oldTarget, from, to);
            }
        }

        MetaspaceCleanup.runNow();

        plugin.getHotSwapManager().temporarilyIgnore(target.getName(), 5000L);
        if (oldTarget != null) {
            plugin.getHotSwapManager().temporarilyIgnore(oldTarget.getName(), 5000L);
        }

        boolean moved = moveFileWithRetry(staged, target.toPath());
        if (!moved) {
            Log.warn("updateinstaller.jar-overwrite-failed", "plugin", identity.pluginName());
            restore(jarBackup, folderBackup, oldTarget, identity, loaded);
            if (restartDependents && !dependents.isEmpty()) {
                loadDependents(identity.pluginName(), dependents);
            }
            return stageForRestart(identity, version, staged, oldTarget, from, to);
        }

        cleanUpEmptyParent(staged);

        if (oldTarget != null && !oldTarget.equals(target) && oldTarget.exists()) {
            boolean removed = deleteFileWithRetry(oldTarget);
            if (!removed) {
                try {
                    oldTarget.deleteOnExit();
                } catch (Throwable ignored) {}
            }
        }

        PluginResult loadResult = plugin.getPluginLifecycleManager().load(target);
        if (!loadResult.success()) {
            Log.warn("updateinstaller.new-version-load-failed", "plugin", identity.pluginName());
            if (!target.equals(oldTarget)) {
                deleteFileWithRetry(target);
            }
            restore(jarBackup, folderBackup, oldTarget, identity, loaded);
            if (restartDependents && !dependents.isEmpty()) {
                loadDependents(identity.pluginName(), dependents);
            }
            Log.warn("updateinstaller.new-version-load-failed", loadResult.error(), "plugin", identity.pluginName());
            return InstallResult.failed(InstallStatus.ROLLED_BACK, identity.pluginName(), "actions.update.details.rolled-back");
        }

        try {
            plugin.getPluginLifecycleManager().getJarIndex().invalidate();
        } catch (Throwable t) {
            Log.debug("updateinstaller.jarindex-invalidate-failed", t);
        }

        if (restartDependents && !dependents.isEmpty()) {
            loadDependents(identity.pluginName(), dependents);
        }
        Log.info("updateinstaller.updated", "plugin", identity.pluginName(), "from", from, "to", to);
        return InstallResult.of(InstallStatus.INSTALLED, identity.pluginName(), from, to);
    }

    private InstallResult stageForRestart(PluginIdentity identity, RemoteVersion version, Path staged, File oldTarget, String from, String to) {
        try {
            File pluginsDir = plugin.getDataFolder().getParentFile();
            File updateFolder = new File(pluginsDir, "update");
            if (!updateFolder.exists()) {
                updateFolder.mkdirs();
            }
            String targetFileName = oldTarget != null ? oldTarget.getName() : (identity.pluginName() + ".jar");
            File updateTarget = new File(updateFolder, targetFileName);
            Files.move(staged, updateTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
            cleanUpEmptyParent(staged);
            Log.info("updateinstaller.staged-for-restart", "plugin", identity.pluginName(), "from", from, "to", to);
            return InstallResult.of(InstallStatus.PENDING_RESTART, identity.pluginName(), from, to);
        } catch (Throwable t) {
            Log.error("updateinstaller.stage-for-restart-failed", t, "plugin", identity.pluginName(), "error", t.getMessage());
            deleteQuietly(staged);
            return InstallResult.failed(InstallStatus.NOT_INSTALLABLE, identity.pluginName(),
                    "actions.update.details.update-folder-write-failed");
        }
    }

    static File determineTargetFile(File oldTarget, RemoteVersion version, PluginIdentity identity, File pluginsDir) {
        if (oldTarget == null) {
            return new File(pluginsDir, identity.pluginName() + ".jar");
        }

        String oldName = oldTarget.getName();
        String currentVer = identity.currentVersion();
        String newVer = version != null ? version.versionNumber() : null;
        int versionAt = currentVer != null && !currentVer.isBlank() ? oldName.lastIndexOf(currentVer) : -1;
        if (versionAt >= 0 && newVer != null && !newVer.isBlank()) {
            String newName = oldName.substring(0, versionAt) + newVer + oldName.substring(versionAt + currentVer.length());
            return new File(oldTarget.getParentFile(), newName);
        }

        return oldTarget;
    }

    private record DependentInfo(String name, File file) {}

    private List<DependentInfo> collectDependentInfos(String pluginName) {
        try {
            List<String> order = plugin.getPluginLifecycleManager()
                    .getDependencyGraph()
                    .calculateCascadeOrder(pluginName, true);
            List<DependentInfo> result = new ArrayList<>();
            for (String name : order) {
                if (name.equalsIgnoreCase(pluginName) || name.equalsIgnoreCase(plugin.getName())) {
                    continue;
                }
                Plugin depPlugin = plugin.getPluginLifecycleManager().getPlugin(name);
                if (depPlugin == null || plugin.getPluginLifecycleManager().isProtected(depPlugin)) {
                    continue;
                }
                File file = plugin.getPluginLifecycleManager().getPluginFile(depPlugin);
                if (file != null && file.exists() && JarValidator.isValidPluginJar(file)) {
                    result.add(new DependentInfo(name, file));
                }
            }
            return result;
        } catch (Throwable t) {
            Log.debug("updateinstaller.dependents-collect-failed", t, "plugin", pluginName);
            return List.of();
        }
    }

    private void loadDependents(String pluginName, List<DependentInfo> dependents) {
        if (dependents.isEmpty()) {
            return;
        }

        Log.info("updateinstaller.restarting-dependents", "plugin", pluginName, "count", String.valueOf(dependents.size()));
        scheduleNextDependentLoad(pluginName, dependents, 0);
    }

    private void scheduleNextDependentLoad(String pluginName, List<DependentInfo> dependents, int index) {
        if (index >= dependents.size()) {
            return;
        }

        DependentInfo info = dependents.get(index);
        PluginResult result = plugin.getPluginLifecycleManager().load(info.file());
        if (!result.success()) {
            Log.warn("updateinstaller.dependent-load-failed", "dependent", info.name(), "plugin", pluginName);
        }

        if (index + 1 < dependents.size()) {
            TaskScheduler.runSyncLater(plugin, () -> scheduleNextDependentLoad(pluginName, dependents, index + 1), 2L);
        }
    }

    private void restore(Path jarBackup, Path folderBackup, File target, PluginIdentity identity, Plugin loaded) {
        if (folderBackup != null && loaded != null && loaded.getDataFolder().exists()) {
            backups.restoreFolder(folderBackup, loaded.getDataFolder());
        }
        if (jarBackup != null && target != null) {
            if (!backups.restore(jarBackup, target)) {
                Log.error("updateinstaller.rollback-failed", "file", target.getName());
                return;
            }
        }
        if (target != null && target.exists()) {
            PluginResult restored = plugin.getPluginLifecycleManager().load(target);
            if (!restored.success()) {
                Log.error("updateinstaller.rollback-load-failed", "plugin", identity.pluginName());
            }
        }
    }

    private boolean moveFileWithRetry(Path source, Path destination) {
        for (int i = 0; i < 5; i++) {
            try {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Throwable t) {
                Log.debug("updateinstaller.move-attempt-failed", t, "attempt", String.valueOf(i + 1), "source", source.getFileName().toString(), "destination", destination.getFileName().toString());
                MetaspaceCleanup.runNow();
                try {
                    Thread.sleep(60L * (i + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return false;
    }

    private boolean deleteFileWithRetry(File file) {
        if (!file.isFile()) return !file.exists();
        for (int i = 0; i < 5; i++) {
            try {
                if (Files.deleteIfExists(file.toPath())) {
                    return true;
                }
            } catch (Throwable t) {
                Log.debug("updateinstaller.delete-attempt-failed", t, "attempt", String.valueOf(i + 1), "file", file.getName());
                MetaspaceCleanup.runNow();
            }
            try {
                Thread.sleep(60L * (i + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return !file.exists();
            }
        }
        return !file.exists();
    }

    private void cleanUpEmptyParent(@Nullable Path path) {
        if (path == null) return;
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        try (var stream = Files.list(parent)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(parent);
            }
        } catch (Throwable t) {
            Log.debug("updateinstaller.empty-folder-cleanup-failed", t, "folder", parent.getFileName().toString());
        }
    }

    private void deleteQuietly(@Nullable Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
            cleanUpEmptyParent(path);
        } catch (Throwable t) {
            Log.debug("updateinstaller.temp-file-delete-failed", t, "file", path.getFileName().toString());
        }
    }
}

