package ru.milkyway.plugmanreloaded.update.install;

import ru.milkyway.plugmanreloaded.update.UpdateModels.*;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.download.DownloadClient;
import ru.milkyway.plugmanreloaded.managers.SafetyManager;
import ru.milkyway.plugmanreloaded.managers.SanitizerManager;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class UpdateInstaller {

    public static final String TEMP_DIR_NAME = ".plugmanreloaded-tmp";

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

    private record Preparation(InstallResult error, Path jarBackup, Path folderBackup, Path staged,
                               List<String> dependencyWarnings, String artifactSha256, long artifactSize, boolean hasLibraries) {}

    public void install(UpdateCandidate candidate, Consumer<InstallResult> callback) {
        install(candidate, true, callback);
    }

    public void install(UpdateCandidate candidate, boolean restartDependents, Consumer<InstallResult> callback) {
        if (candidate == null || candidate.identity() == null || !candidate.installable()) {
            String pluginName = candidate != null && candidate.identity() != null
                    ? candidate.identity().pluginName() : "Unknown";
            TaskScheduler.runSync(plugin, () -> callback.accept(
                    InstallResult.failed(InstallStatus.NOT_INSTALLABLE, pluginName, "actions.update.details.candidate-not-installable")));
            return;
        }
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
            TaskScheduler.runSync(plugin, () -> wrappedCallback.accept(swap(identity, version, prep.staged(), prep.jarBackup(),
                    prep.folderBackup(), restartDependents, prep.dependencyWarnings(), prep.artifactSha256(), prep.artifactSize(), prep.hasLibraries())));
        });
    }

    private Path stagedPath(PluginIdentity identity, RemoteVersion version) {
        String fileName = version.fileName() != null && !version.fileName().isBlank()
                ? version.fileName()
                : identity.pluginName() + "-" + version.versionNumber() + ".jar";
        String txId = UUID.randomUUID().toString().substring(0, 8);
        return plugin.getDataFolder().getParentFile().toPath()
                .resolve(TEMP_DIR_NAME)
                .resolve("up_" + txId)
                .resolve(fileName.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private Preparation prepare(PluginIdentity identity, RemoteVersion version) {
        Path staged = stagedPath(identity, version);

        DownloadClient.Downloaded downloaded = DownloadClient.download(version.downloadUrl(), staged, userAgent);
        if (downloaded == null) {
            return new Preparation(InstallResult.failed(InstallStatus.DOWNLOAD_FAILED, identity.pluginName(), "actions.update.details.download-failed"), null, null, null, List.of(), "", -1L, false);
        }

        String hashProblem = verifyHash(version, downloaded);
        if (hashProblem != null) {
            deleteQuietly(staged);
            return new Preparation(InstallResult.failed(InstallStatus.HASH_MISMATCH, identity.pluginName(), hashProblem), null, null, null, List.of(), "", -1L, false);
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
            return new Preparation(InstallResult.failed(outcome, identity.pluginName(), report.errorMessage()), null, null, null, List.of(), "", -1L, false);
        }

        Path jarBackup = backups.backup(identity.pluginName(), identity.currentVersion(), identity.jarFile());
        if (jarBackup == null) {
            deleteQuietly(staged);
            return new Preparation(InstallResult.failed(InstallStatus.NOT_INSTALLABLE, identity.pluginName(),
                    "actions.update.details.backup-failed"), null, null, null, List.of(), "", -1L, false);
        }

        Plugin loadedPlugin = plugin.getPluginLifecycleManager().getPlugin(identity.pluginName());
        Path folderBackup = (loadedPlugin != null && loadedPlugin.getDataFolder().exists())
                ? backups.backupFolder(identity.pluginName(), loadedPlugin.getDataFolder())
                : null;

        PluginJarIndex.JarDescriptor stagedDesc = PluginJarIndex.readDescriptor(staged.toFile());
        List<String> warnings = List.of();
        if (stagedDesc != null && stagedDesc.depend() != null && !stagedDesc.depend().isEmpty()) {
            warnings = checkDependencyUpdates(identity.pluginName(), stagedDesc.depend());
        }

        return new Preparation(null, jarBackup, folderBackup, staged, warnings, downloaded.sha256(), downloaded.size(), stagedDesc != null && stagedDesc.hasLibraries());
    }

    private List<String> checkDependencyUpdates(String pluginName, List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty() || plugin == null) return List.of();
        List<String> warnings = new ArrayList<>();
        List<UpdateCandidate> recent = plugin.getUpdateService().getLastResults();
        for (String dep : dependencies) {
            Plugin installedDep = plugin.getPluginLifecycleManager().getPlugin(dep);
            if (installedDep == null) continue;
            UpdateCandidate candidate = null;
            if (recent != null) {
                for (UpdateCandidate c : recent) {
                    if (c.identity().pluginName().equalsIgnoreCase(dep)) {
                        candidate = c;
                        break;
                    }
                }
            }
            if (candidate == null) {
                try {
                    candidate = plugin.getUpdateService().checkSync(installedDep, false);
                } catch (Throwable ignored) {}
            }
            if (candidate != null && candidate.status().hasNewerVersion() && candidate.version() != null) {
                String newVer = candidate.version().versionNumber();
                Log.warn("updateinstaller.dependency-update-available",
                        "plugin", pluginName,
                        "dependency", dep,
                        "newVersion", newVer);
                warnings.add(dep + ":" + newVer);
            }
        }
        return warnings;
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

    private InstallResult swap(PluginIdentity identity, RemoteVersion version, Path staged, Path jarBackup,
                               Path folderBackup, boolean restartDependents, List<String> dependencyWarnings,
                               String artifactSha256, long artifactSize, boolean hasLibraries) {
        File oldTarget = identity.jarFile();
        String from = identity.currentVersion();
        String to = version.versionNumber();

        File target = determineTargetFile(oldTarget, version, identity, plugin.getDataFolder().getParentFile());

        Plugin loaded = plugin.getPluginLifecycleManager().getPlugin(identity.pluginName());
        SafetyManager.PluginRiskLevel risk = loaded != null
                ? plugin.getPluginLifecycleManager().getSafetyManager().assess(loaded).riskLevel()
                : SafetyManager.PluginRiskLevel.SAFE;
        boolean isUnsafe = risk == SafetyManager.PluginRiskLevel.UNLOADABLE_HOSTILE
                || risk == SafetyManager.PluginRiskLevel.CRITICAL_PROTECTED
                || risk == SafetyManager.PluginRiskLevel.API_PROVIDER
                || risk == SafetyManager.PluginRiskLevel.LOW_LEVEL_NETWORK
                || plugin.getConfigManager().isUnsafeToUnload(identity.pluginName())
                || hasLibraries;

        if (isUnsafe) {
            return stageForRestart(identity, version, staged, oldTarget, from, to, dependencyWarnings,
                    artifactSha256, artifactSize);
        }

        SanitizerManager.closeAllOnlineInventories();

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
                return stageForRestart(identity, version, staged, oldTarget, from, to, dependencyWarnings,
                        artifactSha256, artifactSize);
            }
        }

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
            return stageForRestart(identity, version, staged, oldTarget, from, to, dependencyWarnings,
                    artifactSha256, artifactSize);
        }

        cleanUpEmptyParents(staged);

        boolean oldTargetNeedsDeleteOnExit = false;
        if (oldTarget != null && !oldTarget.equals(target) && oldTarget.exists()) {
            boolean removed = deleteFileWithRetry(oldTarget);
            if (!removed) {
                oldTargetNeedsDeleteOnExit = true;
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
            return InstallResult.failed(InstallStatus.ROLLED_BACK, identity.pluginName(), "actions.update.details.rolled-back", dependencyWarnings);
        }

        if (oldTargetNeedsDeleteOnExit && oldTarget != null && oldTarget.exists()) {
            try {
                oldTarget.deleteOnExit();
            } catch (Throwable ignored) {}
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
        persistInstalledSource(identity, version, artifactSha256, artifactSize, target.getName());
        return InstallResult.of(InstallStatus.INSTALLED, identity.pluginName(), from, to, dependencyWarnings);
    }

    private InstallResult stageForRestart(PluginIdentity identity, RemoteVersion version, Path staged, File oldTarget,
                                          String from, String to, List<String> dependencyWarnings,
                                          String artifactSha256, long artifactSize) {
        try {
            File pluginsDir = plugin.getDataFolder().getParentFile();
            File updateFolder = null;
            try {
                if (Bukkit.getServer() != null) {
                    updateFolder = Bukkit.getUpdateFolderFile();
                }
            } catch (Throwable ignored) {}
            if (updateFolder == null) {
                updateFolder = new File(pluginsDir, "update");
            }
            if (!updateFolder.exists()) {
                updateFolder.mkdirs();
            }
            String targetFileName = oldTarget != null ? oldTarget.getName() : (identity.pluginName() + ".jar");
            File updateTarget = new File(updateFolder, targetFileName);
            replacePendingArtifact(staged, updateTarget, backups, identity.pluginName(), version.versionNumber());
            cleanUpEmptyParents(staged);
            persistPendingSource(identity, version, artifactSha256, artifactSize, updateTarget.getName());
            Log.info("updateinstaller.staged-for-restart", "plugin", identity.pluginName(), "from", from, "to", to);
            return InstallResult.of(InstallStatus.PENDING_RESTART, identity.pluginName(), from, to, dependencyWarnings);
        } catch (Throwable t) {
            Log.error("updateinstaller.stage-for-restart-failed", t, "plugin", identity.pluginName(), "error", t.getMessage());
            deleteQuietly(staged);
            return InstallResult.failed(InstallStatus.NOT_INSTALLABLE, identity.pluginName(),
                    "actions.update.details.update-folder-write-failed", dependencyWarnings);
        }
    }

    private void persistInstalledSource(PluginIdentity identity, RemoteVersion version, String artifactSha256,
                                        long artifactSize, String artifactFile) {
        try {
            SourceCatalog.CatalogSource pinned = SourceCatalog.pinnedInstallation(
                    version.sourceId(), version.projectRef(), version.projectUrl(), version.versionNumber(),
                    artifactSha256, artifactSize, artifactFile
            );
            String language = plugin.getConfigManager().getMainConfig().getLanguage();
            if (SourceCatalog.writeUserEntry(SourceCatalog.resolveFile(plugin), identity.pluginName(),
                    identity.mainClass(), pinned, language)) {
                plugin.getUpdateService().reload();
            }
        } catch (Throwable t) {
            Log.warn("updateinstaller.source-write-failed", t, "plugin", identity.pluginName());
        }
    }

    private void persistPendingSource(PluginIdentity identity, RemoteVersion version, String artifactSha256,
                                      long artifactSize, String artifactFile) {
        try {
            SourceCatalog.CatalogSource pending = SourceCatalog.pendingInstallation(
                    version.sourceId(), version.projectRef(), version.projectUrl(), version.versionNumber(),
                    artifactSha256, artifactSize, artifactFile
            );
            String language = plugin.getConfigManager().getMainConfig().getLanguage();
            if (SourceCatalog.writeUserEntry(SourceCatalog.resolveFile(plugin), identity.pluginName(),
                    identity.mainClass(), pending, language)) {
                plugin.getUpdateService().reload();
            }
        } catch (Throwable t) {
            Log.warn("updateinstaller.pending-source-write-failed", t, "plugin", identity.pluginName());
        }
    }

    static File determineTargetFile(File oldTarget, RemoteVersion version, PluginIdentity identity, File pluginsDir) {
        if (oldTarget == null) {
            return new File(pluginsDir, identity.pluginName() + ".jar");
        }

        String oldName = oldTarget.getName();
        String currentVer = identity.currentVersion();
        String newVer = version != null ? version.versionNumber() : null;
        if (newVer != null) {
            newVer = newVer.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        int versionAt = currentVer != null && !currentVer.isBlank() ? oldName.lastIndexOf(currentVer) : -1;
        if (versionAt >= 0 && newVer != null && !newVer.isBlank() && !newVer.equalsIgnoreCase(currentVer)) {
            String newName = oldName.substring(0, versionAt) + newVer + oldName.substring(versionAt + currentVer.length());
            return new File(oldTarget.getParentFile(), newName);
        }

        return oldTarget;
    }

    private record DependentInfo(String name, File file) {}

    private List<DependentInfo> collectDependentInfos(String pluginName) {
        try {
            List<String> order = plugin.getPluginLifecycleManager()
                    .getDependencyManager()
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
        for (DependentInfo info : dependents) {
            PluginResult result = plugin.getPluginLifecycleManager().load(info.file());
            if (!result.success()) {
                Log.warn("updateinstaller.dependent-load-failed", "dependent", info.name(), "plugin", pluginName);
            }
        }
    }

    private void restore(Path jarBackup, Path folderBackup, File target, PluginIdentity identity, Plugin loaded) {
        if (folderBackup != null && loaded != null && loaded.getDataFolder().exists()) {
            backups.restoreFolder(folderBackup, loaded.getDataFolder());
        }
        if (jarBackup != null && target != null) {
            if (!backups.restore(jarBackup, target)) {
                Log.error("updateinstaller.rollback-failed", "file", target.getName());
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
        for (int i = 0; i < 3; i++) {
            try {
                moveReplacing(source, destination);
                return true;
            } catch (Throwable t) {
                Log.debug("updateinstaller.move-attempt-failed", t, "attempt", String.valueOf(i + 1), "source", source.getFileName().toString(), "destination", destination.getFileName().toString());
            }
        }
        return false;
    }

    private static void moveReplacing(Path source, Path destination) throws Exception {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static @Nullable Path replacePendingArtifact(
            Path staged,
            File target,
            @Nullable BackupStore backups,
            String pluginName,
            String version
    ) throws Exception {
        if (staged == null || !Files.isRegularFile(staged)) {
            throw new NoSuchFileException(staged != null ? staged.toString() : "null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Path backup = null;
        if (target.exists() && target.isFile() && backups != null) {
            backup = backups.backup(pluginName, version, target);
        }
        moveReplacing(staged, target.toPath());
        return backup;
    }

    private boolean deleteFileWithRetry(File file) {
        if (!file.isFile()) return !file.exists();
        for (int i = 0; i < 3; i++) {
            try {
                if (Files.deleteIfExists(file.toPath())) {
                    return true;
                }
            } catch (Throwable t) {
                Log.debug("updateinstaller.delete-attempt-failed", t, "attempt", String.valueOf(i + 1), "file", file.getName());
            }
            try {
                if (file.delete()) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return !file.exists();
    }

    public static void cleanUpEmptyParents(@Nullable Path path) {
        if (path == null) return;
        Path dir = path.getParent();
        while (dir != null && Files.isDirectory(dir)) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
            if (!name.startsWith("up_") && !name.equals(TEMP_DIR_NAME)) {
                break;
            }
            try (var stream = Files.list(dir)) {
                if (stream.findAny().isEmpty()) {
                    Files.deleteIfExists(dir);
                    dir = dir.getParent();
                } else {
                    break;
                }
            } catch (Throwable t) {
                Log.debug("updateinstaller.empty-folder-cleanup-failed", t, "folder", dir.getFileName() != null ? dir.getFileName().toString() : "");
                break;
            }
        }
    }

    public static void cleanStaleTempDirectories(@Nullable File pluginsDir) {
        cleanStaleTempDirectories(pluginsDir, ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    public static void cleanStaleTempDirectories(@Nullable File pluginsDir, long maxModifiedThresholdMillis) {
        if (pluginsDir == null || !pluginsDir.isDirectory()) return;
        Path tmpDir = pluginsDir.toPath().resolve(TEMP_DIR_NAME);
        if (!Files.exists(tmpDir) || !Files.isDirectory(tmpDir)) return;
        try (var stream = Files.list(tmpDir)) {
            List<Path> entries = stream.toList();
            for (Path entry : entries) {
                String name = entry.getFileName() != null ? entry.getFileName().toString() : "";
                if (!name.startsWith("up_") && !name.startsWith("tx_") && !name.startsWith("inspect_")) {
                    continue;
                }
                try {
                    long lastMod = Files.getLastModifiedTime(entry).toMillis();
                    if (lastMod < maxModifiedThresholdMillis) {
                        deleteRecursivelyQuietly(entry);
                    }
                } catch (Throwable t) {
                    Log.debug("updateinstaller.stale-entry-delete-failed", t, "entry", name);
                }
            }
        } catch (Throwable t) {
            Log.debug("updateinstaller.stale-cleanup-failed", t);
        }

        try (var stream = Files.list(tmpDir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(tmpDir);
            }
        } catch (Throwable t) {
            Log.debug("updateinstaller.temp-parent-delete-failed", t);
        }
    }

    public static void deleteRecursivelyQuietly(@Nullable Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void deleteQuietly(@Nullable Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
            cleanUpEmptyParents(path);
        } catch (Throwable t) {
            Log.debug("updateinstaller.temp-file-delete-failed", t, "file", path.getFileName().toString());
        }
    }
}
