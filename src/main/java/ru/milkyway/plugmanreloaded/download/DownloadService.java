package ru.milkyway.plugmanreloaded.download;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.download.models.DownloadStatus;
import ru.milkyway.plugmanreloaded.download.models.DownloadResult;
import ru.milkyway.plugmanreloaded.download.models.DependencyTree;
import ru.milkyway.plugmanreloaded.download.models.SearchResultEntry;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.update.input.SourceUrlParser;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public class DownloadService {

    private final PlugManReloaded plugin;
    private final PluginSearch searchEngine;
    private final DependencyResolver dependencyResolver;
    private final PluginDownloader coordinator;
    private final DownloadLocks lockManager;

    public DownloadService(PlugManReloaded plugin, ServerProfile serverProfile, SourceCatalog catalog, String userAgent) {
        this.plugin = plugin;
        this.searchEngine = new PluginSearch(plugin, serverProfile, catalog);
        this.coordinator = new PluginDownloader(plugin, serverProfile, userAgent);
        this.dependencyResolver = new DependencyResolver(plugin, searchEngine, catalog,
                (entry, stagingDir) -> stageForInspection(entry, stagingDir));
        this.lockManager = new DownloadLocks();
    }

    public List<SearchResultEntry> search(String query, String preferredSource, int limit) {
        return searchEngine.search(query, preferredSource, limit);
    }

    public void downloadFromUrl(
            String url,
            boolean autoConfirmDeps,
            boolean withSoftDeps,
            Consumer<DownloadResult> callback,
            Consumer<DependencyTree> promptDepsCallback
    ) {
        var parsed = SourceUrlParser.parse(url);
        if (!parsed.success()) {
            callback.accept(DownloadResult.failed(DownloadStatus.INVALID_PLUGIN, "URL", parsed.errorReason()));
            return;
        }

        var source = parsed.source();
        TaskScheduler.runAsync(plugin, () -> {
            SearchResultEntry entry = null;
            try {
                List<SearchResultEntry> hits = searchEngine.search(url, source.sourceId(), 1);
                if (hits != null && !hits.isEmpty()) {
                    entry = hits.get(0);
                }
            } catch (Throwable t) {
                Log.debug("downloadservice.search-failed", t, "url", url);
            }

            if (entry == null) {
                entry = new SearchResultEntry(
                        source.sourceId(), source.ref(), source.ref(), "Unknown", "1.0", "",
                        source.url() != null ? source.url() : url, null, 0, 0, 100.0,
                        Collections.emptyList(), List.of("paper", "spigot"), Collections.emptyList(),
                        null, null, null, false, true
                );
            }

            final SearchResultEntry targetEntry = entry;
            TaskScheduler.runSync(plugin, () -> {
                resolveAndDownload(targetEntry, autoConfirmDeps, withSoftDeps, callback, promptDepsCallback);
            });
        });
    }

    public void resolveAndDownload(
            SearchResultEntry targetEntry,
            boolean autoConfirmDeps,
            boolean withSoftDeps,
            Consumer<DownloadResult> callback,
            Consumer<DependencyTree> promptDepsCallback
    ) {
        String targetName = targetEntry.title() != null ? targetEntry.title() : targetEntry.projectId();
        if (!lockManager.tryLock(targetName)) {
            callback.accept(DownloadResult.failed(DownloadStatus.LOCKED, targetName, "actions.download.details.locked"));
            return;
        }

        TaskScheduler.runAsync(plugin, () -> {
            Path inspectionDir = null;
            try {
                inspectionDir = plugin.getDataFolder().getParentFile().toPath()
                        .resolve(".plugmanreloaded-tmp")
                        .resolve("inspect_" + UUID.randomUUID());

                PluginDownloader.StageAttempt attempt = coordinator.stageForInspection(targetEntry, inspectionDir);
                if (attempt.item() == null || attempt.file() == null) {
                    lockManager.unlock(targetName);
                    coordinator.cleanupInspectionDir(inspectionDir);
                    PluginDownloader.StageFailure failure = attempt.failure();
                    DownloadStatus outcome = failure != null ? failure.outcome() : DownloadStatus.DOWNLOAD_FAILED;
                    String detail = failure != null ? failure.detail() : "actions.download.details.deps-check-failed";
                    TaskScheduler.runSync(plugin, () -> callback.accept(
                            DownloadResult.failed(outcome, targetName, targetEntry.sourceId(), detail)
                    ));
                    return;
                }

                File targetJar = attempt.file();

                DependencyTree tree = dependencyResolver.resolve(targetJar, targetEntry, withSoftDeps, inspectionDir);
                coordinator.cleanupInspectionDir(inspectionDir);

                if (tree.hasCycles()) {
                    lockManager.unlock(targetName);
                    String details = tree.cycleDetails() != null ? tree.cycleDetails()
                            : plugin.getConfigManager().text("actions.download.details.cycle-unknown");
                    TaskScheduler.runSync(plugin, () -> callback.accept(
                            DownloadResult.failed(DownloadStatus.CIRCULAR_DEPENDENCIES, targetName, details)
                    ));
                    return;
                }

                if (!tree.isFullyResolvable()) {
                    lockManager.unlock(targetName);
                    String missing = String.join(", ", tree.unresolvableDependencies());
                    TaskScheduler.runSync(plugin, () -> callback.accept(
                            DownloadResult.failed(DownloadStatus.DEPENDENCIES_REQUIRED, targetName, missing)
                    ));
                    return;
                }

                if (!autoConfirmDeps && promptDepsCallback != null) {
                    lockManager.unlock(targetName);
                    TaskScheduler.runSync(plugin, () -> promptDepsCallback.accept(tree));
                    return;
                }

                executeTransactionInternal(targetName, tree, callback);
            } catch (Throwable t) {
                lockManager.unlock(targetName);
                if (inspectionDir != null) {
                    coordinator.cleanupInspectionDir(inspectionDir);
                }
                TaskScheduler.runSync(plugin, () -> callback.accept(
                        DownloadResult.failed(DownloadStatus.ACTIVATION_FAILED, targetName, t.getMessage())
                ));
            }
        });
    }

    private @Nullable File stageForInspection(SearchResultEntry entry, @Nullable Path stagingDir) {
        if (stagingDir == null) {
            return null;
        }
        return coordinator.stageForInspection(entry, stagingDir.resolve("dep_" + UUID.randomUUID())).file();
    }

    public void confirmAndExecuteTree(DependencyTree tree, Consumer<DownloadResult> callback) {
        String targetName = tree.targetPluginName();
        if (!lockManager.tryLock(targetName)) {
            callback.accept(DownloadResult.failed(DownloadStatus.LOCKED, targetName, "actions.download.details.locked"));
            return;
        }

        TaskScheduler.runAsync(plugin, () -> {
            try {
                executeTransactionInternal(targetName, tree, callback);
            } catch (Throwable t) {
                lockManager.unlock(targetName);
                TaskScheduler.runSync(plugin, () -> callback.accept(
                        DownloadResult.failed(DownloadStatus.ACTIVATION_FAILED, targetName, t.getMessage())
                ));
            }
        });
    }

    private void executeTransactionInternal(String lockKey, DependencyTree tree, Consumer<DownloadResult> callback) {
        coordinator.executeInstallTransaction(
                tree.targetEntry(),
                tree.requiredDependencies(),
                res -> {
                    lockManager.unlock(lockKey);
                    callback.accept(res);
                }
        );
    }

    public PluginSearch getSearchEngine() {
        return searchEngine;
    }

    public DependencyResolver getDependencyResolver() {
        return dependencyResolver;
    }

    public DownloadLocks getLockManager() {
        return lockManager;
    }
}

