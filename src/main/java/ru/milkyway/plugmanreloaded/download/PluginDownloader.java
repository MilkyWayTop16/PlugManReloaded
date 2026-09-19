package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.update.install.UpdateInstaller;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginInfo;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.update.PluginMatcher;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;
import ru.milkyway.plugmanreloaded.utils.JarValidator.PreFlightStatus;

public class PluginDownloader {
   private final PlugManReloaded plugin;
   private final DownloadResolver downloadResolver;
   private final String userAgent;

   public PluginDownloader(PlugManReloaded plugin, ServerProfile serverProfile, String userAgent) {
      this.plugin = plugin;
      this.downloadResolver = new DownloadResolver(plugin, serverProfile);
      this.userAgent = userAgent;
   }

   public void executeInstallTransaction(DownloadModels.SearchResultEntry targetEntry, List<DownloadModels.SearchResultEntry> dependencyEntries, List<String> existingDisabledToEnable, List<String> existingUnloadedToLoad, Consumer<DownloadModels.DownloadResult> callback) {
      this.executeInstallTransactionInternal(targetEntry, dependencyEntries, existingDisabledToEnable, existingUnloadedToLoad, callback);
   }

   public void executeInstallTransaction(DownloadModels.SearchResultEntry targetEntry, List<DownloadModels.SearchResultEntry> dependencyEntries, Consumer<DownloadModels.DownloadResult> callback) {
      this.executeInstallTransactionInternal(targetEntry, dependencyEntries, List.of(), List.of(), callback);
   }

   private void executeInstallTransactionInternal(DownloadModels.SearchResultEntry targetEntry, List<DownloadModels.SearchResultEntry> dependencyEntries, List<String> existingDisabledToEnable, List<String> existingUnloadedToLoad, Consumer<DownloadModels.DownloadResult> callback) {
      UUID txId = UUID.randomUUID();
      Path stagingDir = this.plugin.getDataFolder().getParentFile().toPath().resolve(UpdateInstaller.TEMP_DIR_NAME).resolve("tx_" + String.valueOf(txId));
      TaskScheduler.runAsync(this.plugin, () -> {
         try {
            Files.createDirectories(stagingDir);
            List<DownloadModels.SearchResultEntry> allEntries = new ArrayList();
            if (dependencyEntries != null) {
               allEntries.addAll(dependencyEntries);
            }

            allEntries.add(targetEntry);
            List<StagedItem> stagedItems = new ArrayList();

            for(DownloadModels.SearchResultEntry entry : allEntries) {
               StageAttempt attempt = this.stageAndValidate(entry, stagingDir);
               if (attempt.item() == null) {
                  StageFailure failure = attempt.failure() != null ? attempt.failure() : new StageFailure(DownloadModels.DownloadStatus.DOWNLOAD_FAILED, "actions.download.details.stage-failed");
                  cleanupQuietly(stagingDir);
                  TaskScheduler.runSync(this.plugin, () -> callback.accept(DownloadModels.DownloadResult.failed(failure.outcome(), entry.title(), entry.sourceId(), failure.detail())));
                  return;
               }

               stagedItems.add(attempt.item());
            }

            TaskScheduler.runSync(this.plugin, () -> {
               DownloadModels.DownloadResult result = this.commitAndActivate(stagedItems, existingDisabledToEnable, existingUnloadedToLoad);
               if (result.outcome() != DownloadModels.DownloadStatus.ACTIVATION_FAILED) {
                  cleanupQuietly(stagingDir);
               }

               callback.accept(result);
            });
         } catch (LinkageError | Exception t) {
            cleanupQuietly(stagingDir);
            Log.error("plugindownloader.transaction-error", t, new String[]{"error", ((Throwable)t).getMessage()});
            TaskScheduler.runSync(this.plugin, () -> callback.accept(DownloadModels.DownloadResult.failed(DownloadModels.DownloadStatus.ACTIVATION_FAILED, targetEntry.title(), targetEntry.sourceId(), t.getMessage())));
         }

      });
   }

   public StageAttempt stageForInspection(DownloadModels.@Nullable SearchResultEntry entry, Path inspectionDir) {
      if (entry != null && inspectionDir != null) {
         try {
            Files.createDirectories(inspectionDir);
         } catch (LinkageError | Exception t) {
            Log.debug("plugindownloader.catalog-create-failed", t, new String[0]);
            return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.DOWNLOAD_FAILED, ((Throwable)t).getMessage());
         }

         return this.stageAndValidate(entry, inspectionDir);
      } else {
         return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.DOWNLOAD_FAILED, "actions.download.details.stage-failed");
      }
   }

   public @Nullable File stageForInspectionFile(DownloadModels.@Nullable SearchResultEntry entry, Path inspectionDir) {
      return this.stageForInspection(entry, inspectionDir).file();
   }

   public void cleanupInspectionDir(Path dir) {
      cleanupQuietly(dir);
   }

   private StageAttempt stageAndValidate(DownloadModels.SearchResultEntry entry, Path stagingDir) {
      try {
         DownloadResolver.DownloadResolution resolution = this.downloadResolver.resolve(entry);
         DownloadResolver.ResolvedDownload info = resolution.info();
         if (info != null && info.downloadUrl() != null && !info.downloadUrl().isBlank()) {
            String safeName = info.fileName() != null && !info.fileName().isBlank() ? info.fileName().replaceAll("[^A-Za-z0-9._-]", "_") : entry.projectId().replaceAll("[^A-Za-z0-9._-]", "_") + ".jar";
            String var10001 = String.valueOf(UUID.randomUUID());
            Path targetStaged = stagingDir.resolve(var10001 + "_" + safeName + ".tmp");
            DownloadClient.Downloaded downloaded = DownloadClient.download(info.downloadUrl(), targetStaged, this.userAgent);
            if (downloaded != null && Files.exists(targetStaged, new LinkOption[0])) {
               if (!validateMagicBytes(targetStaged)) {
                  Files.deleteIfExists(targetStaged);
                  return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.INVALID_PLUGIN, "actions.download.details.not-a-jar");
               } else {
                  if (info.sha512() != null && !info.sha512().isBlank()) {
                     String calcSha512 = calculateHash(targetStaged, "SHA-512");
                     if (!info.sha512().equalsIgnoreCase(calcSha512)) {
                        Files.deleteIfExists(targetStaged);
                        return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.HASH_MISMATCH, "actions.download.details.sha512-mismatch");
                     }
                  } else if (info.sha256() != null && !info.sha256().isBlank() && !info.sha256().equalsIgnoreCase(downloaded.sha256())) {
                     Files.deleteIfExists(targetStaged);
                     return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.HASH_MISMATCH, "actions.download.details.sha256-mismatch");
                  }

                  File stagedFile = targetStaged.toFile();
                  JarValidator.PreFlightReport report = JarValidator.validatePreFlight(stagedFile, (String)null, false);
                  if (!report.isValid() && report.status() != PreFlightStatus.MISSING_DEPENDENCIES) {
                     DownloadModels.DownloadStatus var10000;
                     switch (report.status()) {
                        case INCOMPATIBLE_JAVA -> var10000 = DownloadModels.DownloadStatus.INCOMPATIBLE_JAVA;
                        case NO_DESCRIPTOR -> var10000 = DownloadModels.DownloadStatus.INVALID_MANIFEST;
                        default -> var10000 = DownloadModels.DownloadStatus.INVALID_MANIFEST;
                     }

                     DownloadModels.DownloadStatus outcome = var10000;
                     Files.deleteIfExists(targetStaged);
                     return PluginDownloader.StageAttempt.failed(outcome, report.errorMessage());
                  } else {
                     String declaredName = report.declaredName() != null ? report.declaredName() : entry.title();
                     if (!artifactMatchesExpectedProject(entry, declaredName)) {
                        Files.deleteIfExists(targetStaged);
                        return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.INVALID_PLUGIN, "actions.download.details.identity-mismatch");
                     } else {
                        boolean requiresRestart = report.hasBootstrapper() || report.isPaperPlugin() && PlatformDetector.isModernPaper() || JarValidator.hasLibraries(stagedFile);
                        String ver = info.versionNumber() != null ? info.versionNumber() : (report.declaredVersion() != null ? report.declaredVersion() : "1.0");
                        return PluginDownloader.StageAttempt.success(new StagedItem(targetStaged, declaredName, ver, entry.sourceId(), entry.projectId(), entry.url(), requiresRestart, entry, downloaded.sha256(), Files.size(targetStaged)));
                     }
                  }
               }
            } else {
               return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.DOWNLOAD_FAILED, "actions.download.details.network");
            }
         } else {
            Log.debug("plugindownloader.direct-link-failed", new String[]{"title", entry.title()});
            String detail = resolution.failureDetail() != null ? resolution.failureDetail() : "actions.download.details.no-direct-link";
            return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.DOWNLOAD_FAILED, detail);
         }
      } catch (LinkageError | Exception t) {
         Log.debug("plugindownloader.stage-validate-failed", t, new String[]{"title", entry.title()});
         return PluginDownloader.StageAttempt.failed(DownloadModels.DownloadStatus.DOWNLOAD_FAILED, ((Throwable)t).getMessage());
      }
   }

   private DownloadModels.DownloadResult commitAndActivate(List<StagedItem> stagedItems, List<String> existingDisabledToEnable, List<String> existingUnloadedToLoad) {
      return (new DownloadTransaction(this.plugin, (item, target) -> this.saveCatalogSource(item.declaredName(), item, target, false), (item, target) -> this.saveCatalogSource(item.declaredName(), item, target, true), this::refreshAfterInstall)).commit(stagedItems, existingDisabledToEnable != null ? existingDisabledToEnable : List.of(), existingUnloadedToLoad != null ? existingUnloadedToLoad : List.of());
   }

   private DownloadModels.DownloadResult commitAndActivate(List<StagedItem> stagedItems) {
      return this.commitAndActivate(stagedItems, List.of(), List.of());
   }

   private void refreshAfterInstall() {
      try {
         this.plugin.getPluginLifecycleManager().getBrigadierManager().syncCommands();
         this.plugin.getPluginLifecycleManager().getJarIndex().invalidate();
         this.plugin.getUpdateService().reload();
      } catch (LinkageError | Exception t) {
         Log.debug("plugindownloader.post-install-refresh-failed", t, new String[0]);
      }

   }

   private void saveCatalogSource(String pluginName, StagedItem item, File target, boolean pending) {
      try {
         File customSourcesFile = SourceCatalog.resolveFile(this.plugin);
         String mainClass = null;
         Plugin p = this.plugin.getPluginLifecycleManager().getPlugin(pluginName);
         if (p != null) {
            mainClass = p.getDescription().getMain();
         } else {
            File targetFile = this.plugin.getPluginLifecycleManager().getJarIndex().find(pluginName);
            if (targetFile != null && targetFile.exists()) {
               PluginInfo info = PluginMetaHelper.fromJarFile(targetFile);
               if (info != null && info.mainClass() != null && !info.mainClass().isBlank()) {
                  mainClass = info.mainClass();
               }
            }
         }

         SourceCatalog.CatalogSource cat = pending ? SourceCatalog.pendingInstallation(item.sourceId(), item.projectRef(), item.pageUrl(), item.version(), item.artifactSha256(), item.artifactSize(), target.getName()) : SourceCatalog.pinnedInstallation(item.sourceId(), item.projectRef(), item.pageUrl(), item.version(), item.artifactSha256(), item.artifactSize(), target.getName());
         String language = this.plugin.getConfigManager().getMainConfig().getLanguage();
         SourceCatalog.writeUserEntry(customSourcesFile, pluginName, mainClass, cat, language);
      } catch (LinkageError | Exception t) {
         Log.warn("plugindownloader.source-write-failed", t, new String[]{"plugin", pluginName});
      }

   }

   private static boolean validateMagicBytes(Path path) {
      try {
         InputStream is = Files.newInputStream(path);

         boolean var3;
         try {
            byte[] header = is.readNBytes(4);
            var3 = header.length == 4 && header[0] == 80 && header[1] == 75 && header[2] == 3 && header[3] == 4;
         } catch (Throwable var5) {
            if (is != null) {
               try {
                  is.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }
            }

            throw var5;
         }

         if (is != null) {
            is.close();
         }

         return var3;
      } catch (IOException var6) {
         return false;
      }
   }

   private static @Nullable String calculateHash(Path path, String algorithm) {
      try {
         InputStream is = Files.newInputStream(path);

         String var6;
         try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[8192];

            int n;
            while((n = is.read(buf)) != -1) {
               md.update(buf, 0, n);
            }

            var6 = PluginMatcher.toHex(md.digest());
         } catch (Throwable var8) {
            if (is != null) {
               try {
                  is.close();
               } catch (Throwable var7) {
                  var8.addSuppressed(var7);
               }
            }

            throw var8;
         }

         if (is != null) {
            is.close();
         }

         return var6;
      } catch (LinkageError | Exception var9) {
         return null;
      }
   }

   static boolean artifactMatchesExpectedProject(DownloadModels.SearchResultEntry entry, String declaredName) {
      return entry != null && declaredName != null && !declaredName.isBlank() ? PluginMatcher.isExactOrCleanMatch(declaredName, entry.title(), entry.projectId()) : false;
   }

   static @Nullable StageFailure validateStagedItems(Path stagingDirectory, List<StagedItem> items) {
      if (items != null && !items.isEmpty()) {
         Path root = stagingDirectory.toAbsolutePath().normalize();
         Set<String> names = new HashSet();

         for(StagedItem item : items) {
            Path artifact = item.stagedPath().toAbsolutePath().normalize();
            if (!artifact.startsWith(root) || !Files.isRegularFile(artifact, new LinkOption[0])) {
               return new StageFailure(DownloadModels.DownloadStatus.INVALID_PLUGIN, "actions.download.details.invalid-plan-file");
            }

            if (!names.add(PluginMatcher.normalizeName(item.declaredName()))) {
               return new StageFailure(DownloadModels.DownloadStatus.INVALID_PLUGIN, "actions.download.details.duplicate-plugin");
            }

            if (item.artifactSha256() != null && !item.artifactSha256().isBlank()) {
               String calcSha256 = calculateHash(artifact, "SHA-256");
               if (!item.artifactSha256().equalsIgnoreCase(calcSha256)) {
                  return new StageFailure(DownloadModels.DownloadStatus.HASH_MISMATCH, "actions.download.details.sha256-mismatch");
               }
            }
         }

         return null;
      } else {
         return new StageFailure(DownloadModels.DownloadStatus.INVALID_PLUGIN, "actions.download.details.empty-plan");
      }
   }

   static void moveReplacing(Path source, Path target) throws IOException {
      try {
         Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException var3) {
         Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      }

   }

   static @Nullable Path backupForRollback(Path source, Path backupDirectory) throws IOException {
      if (source != null && backupDirectory != null && Files.isRegularFile(source, new LinkOption[0])) {
         Files.createDirectories(backupDirectory);
         String var10001 = String.valueOf(source.getFileName());
         Path backup = backupDirectory.resolve("bak_" + var10001 + "_" + System.nanoTime());
         Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
         return backup;
      } else {
         return null;
      }
   }

   static boolean restoreFileChange(Path target, @Nullable Path backup, boolean createdByTransaction) {
      try {
         if (backup != null && Files.isRegularFile(backup, new LinkOption[0])) {
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
         }

         if (createdByTransaction) {
            Files.deleteIfExists(target);
            return !Files.exists(target, new LinkOption[0]);
         }
      } catch (LinkageError | Exception t) {
         Log.warn("plugindownloader.rollback-restore-failed", t, new String[]{"file", target.getFileName().toString()});
      }

      return false;
   }

   static boolean matchesArtifact(Path path, StagedItem item) {
      try {
         if (item.artifactSize() >= 0L && Files.size(path) != item.artifactSize()) {
            return false;
         } else {
            return item.artifactSha256() == null || item.artifactSha256().isBlank() || item.artifactSha256().equalsIgnoreCase(calculateHash(path, "SHA-256"));
         }
      } catch (IOException var3) {
         return false;
      }
   }

   public static void cleanupQuietly(@Nullable Path path) {
      if (path != null && Files.exists(path, new LinkOption[0])) {
         Path parent = path.getParent();

         try {
            Stream<Path> stream = Files.walk(path);

            List<Path> paths;
            try {
               paths = stream.sorted(Comparator.reverseOrder()).toList();
            } catch (Throwable var11) {
               if (stream != null) {
                  try {
                     stream.close();
                  } catch (Throwable var7) {
                     var11.addSuppressed(var7);
                  }
               }

               throw var11;
            }

            if (stream != null) {
               stream.close();
            }

            for(Path p : paths) {
               try {
                  Files.deleteIfExists(p);
               } catch (Exception e) {
                  Log.debug("plugindownloader.temp-file-delete-failed", e, new String[]{"file", String.valueOf(p)});
               }
            }
         } catch (Exception e) {
            Log.debug("plugindownloader.staging-cleanup-failed", e, new String[0]);
         }

         if (parent != null && Files.isDirectory(parent, new LinkOption[0])) {
            String parentName = parent.getFileName() != null ? parent.getFileName().toString() : "";
            if (parentName.equals(UpdateInstaller.TEMP_DIR_NAME)) {
               try {
                  Stream<Path> stream = Files.list(parent);

                  try {
                     if (stream.findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                     }
                  } catch (Throwable var9) {
                     if (stream != null) {
                        try {
                           stream.close();
                        } catch (Throwable var6) {
                           var9.addSuppressed(var6);
                        }
                     }

                     throw var9;
                  }

                  if (stream != null) {
                     stream.close();
                  }
               } catch (IOException e) {
                  Log.debug("plugindownloader.temp-parent-delete-failed", e, new String[]{"directory", parentName});
               }
            }
         }

      }
   }

   public static record StageFailure(DownloadModels.DownloadStatus outcome, String detail) {
   }

   public static record StageAttempt(@Nullable StagedItem item, @Nullable StageFailure failure) {
      public @Nullable File file() {
         return this.item != null ? this.item.stagedPath().toFile() : null;
      }

      static StageAttempt success(StagedItem item) {
         return new StageAttempt(item, (StageFailure)null);
      }

      static StageAttempt failed(DownloadModels.DownloadStatus outcome, String detail) {
         return new StageAttempt((StagedItem)null, new StageFailure(outcome, detail));
      }
   }

   public static record StagedItem(Path stagedPath, String declaredName, String version, String sourceId, String projectRef, String pageUrl, boolean requiresRestart, DownloadModels.SearchResultEntry sourceEntry, String artifactSha256, long artifactSize) {
      public StagedItem(Path stagedPath, String declaredName, String version, String sourceId, String projectRef, String pageUrl, boolean requiresRestart, DownloadModels.SearchResultEntry sourceEntry) {
         this(stagedPath, declaredName, version, sourceId, projectRef, pageUrl, requiresRestart, sourceEntry, "", -1L);
      }
   }
}
