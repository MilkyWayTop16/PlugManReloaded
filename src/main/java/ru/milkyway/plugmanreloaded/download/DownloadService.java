package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.update.install.UpdateInstaller;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.update.input.SourceUrlParser;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

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
      this.dependencyResolver = new DependencyResolver(plugin, this.searchEngine, catalog, (entry, stagingDir) -> this.stageForInspection(entry, stagingDir));
      this.lockManager = new DownloadLocks();
   }

   public List<DownloadModels.SearchResultEntry> search(String query, String preferredSource, int limit) {
      return this.searchEngine.search(query, preferredSource, limit);
   }

   public void downloadFromUrl(String url, boolean autoConfirmDeps, boolean withSoftDeps, Consumer<DownloadModels.DownloadResult> callback, Consumer<DownloadModels.DependencyTree> promptDepsCallback) {
      SourceUrlParser.ParseResult parsed = SourceUrlParser.parse(url);
      if (!parsed.success()) {
         callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.INVALID_PLUGIN, "URL", parsed.errorReason()));
      } else {
         SourceCatalog.CatalogSource source = parsed.source();
         TaskScheduler.runAsync(this.plugin, () -> {
            DownloadModels.SearchResultEntry entry = null;

            try {
               List<DownloadModels.SearchResultEntry> hits = this.searchEngine.search(source.ref(), source.sourceId(), 1);
               if (hits != null && !hits.isEmpty()) {
                  entry = (DownloadModels.SearchResultEntry)hits.get(0);
               }
            } catch (Throwable t) {
               Log.debug("downloadservice.search-failed", t, new String[]{"url", url});
            }

            if (entry == null) {
               entry = new DownloadModels.SearchResultEntry(source.sourceId(), source.ref(), source.ref(), "Unknown", "1.0", "", source.url() != null ? source.url() : url, (String)null, 0L, 0, (double)100.0F, Collections.emptyList(), List.of("paper", "spigot"), Collections.emptyList(), (String)null, (String)null, (String)null, false, true);
            }

            DownloadModels.SearchResultEntry finalEntry = entry;
            TaskScheduler.runSync(this.plugin, () -> this.resolveAndDownload(finalEntry, autoConfirmDeps, withSoftDeps, callback, promptDepsCallback));
         });
      }
   }

   public void resolveAndDownload(DownloadModels.SearchResultEntry targetEntry, boolean autoConfirmDeps, boolean withSoftDeps, Consumer<DownloadModels.DownloadResult> callback, Consumer<DownloadModels.DependencyTree> promptDepsCallback) {
      String targetName = targetEntry.title() != null ? targetEntry.title() : targetEntry.projectId();
      if (!this.lockManager.tryLock(targetName)) {
         callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.LOCKED, targetName, "actions.download.details.locked"));
      } else {
         TaskScheduler.runAsync(this.plugin, () -> {
            Path inspectionDir = null;

            try {
               inspectionDir = this.plugin.getDataFolder().getParentFile().toPath().resolve(UpdateInstaller.TEMP_DIR_NAME).resolve("inspect_" + String.valueOf(UUID.randomUUID()));
               PluginDownloader.StageAttempt attempt = this.coordinator.stageForInspection(targetEntry, inspectionDir);
               if (attempt.item() == null || attempt.file() == null) {
                  this.lockManager.unlock(targetName);
                  this.coordinator.cleanupInspectionDir(inspectionDir);
                  PluginDownloader.StageFailure failure = attempt.failure();
                  DownloadModels.DownloadStatus outcome = failure != null ? failure.outcome() : DownloadModels.DownloadStatus.DOWNLOAD_FAILED;
                  String detail = failure != null ? failure.detail() : "actions.download.details.deps-check-failed";
                  TaskScheduler.runSync(this.plugin, () -> callback.accept(DownloadModels.DownloadResult.failed(outcome, targetName, targetEntry.sourceId(), detail)));
                  return;
               }

               File targetJar = attempt.file();
               DownloadModels.DependencyTree tree = this.dependencyResolver.resolve(targetJar, targetEntry, withSoftDeps, inspectionDir);
               this.coordinator.cleanupInspectionDir(inspectionDir);
               if (tree.hasCycles()) {
                  this.lockManager.unlock(targetName);
                  String details = tree.cycleDetails() != null ? tree.cycleDetails() : this.plugin.getConfigManager().text("actions.download.details.cycle-unknown", new String[0]);
                  TaskScheduler.runSync(this.plugin, () -> callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.CIRCULAR_DEPENDENCIES, targetName, details)));
                  return;
               }

               if (!tree.isFullyResolvable()) {
                  this.lockManager.unlock(targetName);
                  String missing = String.join(", ", tree.unresolvableDependencies());
                  TaskScheduler.runSync(this.plugin, () -> callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.DEPENDENCIES_REQUIRED, targetName, missing)));
                  return;
               }

               if (!autoConfirmDeps && promptDepsCallback != null) {
                  this.lockManager.unlock(targetName);
                  TaskScheduler.runSync(this.plugin, () -> promptDepsCallback.accept(tree));
                  return;
               }

               this.executeTransactionInternal(targetName, tree, callback);
            } catch (Throwable t) {
               this.lockManager.unlock(targetName);
               if (inspectionDir != null) {
                  this.coordinator.cleanupInspectionDir(inspectionDir);
               }

               TaskScheduler.runSync(this.plugin, () -> callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.ACTIVATION_FAILED, targetName, t.getMessage())));
            }

         });
      }
   }

   private @Nullable File stageForInspection(DownloadModels.SearchResultEntry entry, @Nullable Path stagingDir) {
      return stagingDir == null ? null : this.coordinator.stageForInspection(entry, stagingDir.resolve("dep_" + String.valueOf(UUID.randomUUID()))).file();
   }

   public void confirmAndExecuteTree(DownloadModels.DependencyTree tree, Consumer<DownloadModels.DownloadResult> callback) {
      String targetName = tree.targetPluginName();
      if (!this.lockManager.tryLock(targetName)) {
         callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.LOCKED, targetName, "actions.download.details.locked"));
      } else {
         TaskScheduler.runAsync(this.plugin, () -> {
            try {
               this.executeTransactionInternal(targetName, tree, callback);
            } catch (Throwable t) {
               this.lockManager.unlock(targetName);
               TaskScheduler.runSync(this.plugin, () -> callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.ACTIVATION_FAILED, targetName, t.getMessage())));
            }

         });
      }
   }

   private void executeTransactionInternal(String lockKey, DownloadModels.DependencyTree tree, Consumer<DownloadModels.DownloadResult> callback) {
      List<DownloadModels.SearchResultEntry> dependencies = new ArrayList(tree.orderedDependencies());
      Consumer<DownloadModels.DownloadResult> transactionCallback = (res) -> {
         this.lockManager.unlock(lockKey);
         callback.accept(res);
      };
      if (tree.existingDisabledToEnable().isEmpty() && tree.existingUnloadedToLoad().isEmpty()) {
         this.coordinator.executeInstallTransaction(tree.targetEntry(), dependencies, transactionCallback);
      } else {
         this.coordinator.executeInstallTransaction(tree.targetEntry(), dependencies, tree.existingDisabledToEnable(), tree.existingUnloadedToLoad(), transactionCallback);
      }
   }

   public PluginSearch getSearchEngine() {
      return this.searchEngine;
   }

   public DependencyResolver getDependencyResolver() {
      return this.dependencyResolver;
   }

   public DownloadLocks getLockManager() {
      return this.lockManager;
   }

   public void reloadCatalog(SourceCatalog catalog) {
      this.searchEngine.reloadCatalog(catalog);
      this.dependencyResolver.reloadCatalog(catalog);
   }

   public void shutdown() {
      this.searchEngine.shutdown();
   }

   public static class DownloadLocks {
      private final Set<String> activeLocks = ConcurrentHashMap.newKeySet();

      private String normalize(String name) {
         return name.trim().toLowerCase(Locale.ROOT);
      }

      public boolean tryLock(@Nullable String pluginName) {
         return pluginName != null && !pluginName.isBlank() ? this.activeLocks.add(this.normalize(pluginName)) : false;
      }

      public void unlock(@Nullable String pluginName) {
         if (pluginName != null && !pluginName.isBlank()) {
            this.activeLocks.remove(this.normalize(pluginName));
         }
      }

      public boolean isLocked(@Nullable String pluginName) {
         return pluginName != null && !pluginName.isBlank() ? this.activeLocks.contains(this.normalize(pluginName)) : false;
      }
   }
}
