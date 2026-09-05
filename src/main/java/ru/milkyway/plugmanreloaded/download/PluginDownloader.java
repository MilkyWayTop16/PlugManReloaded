package ru.milkyway.plugmanreloaded.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.download.models.DownloadStatus;
import ru.milkyway.plugmanreloaded.download.models.DownloadResult;
import ru.milkyway.plugmanreloaded.download.models.SearchResultEntry;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.update.*;
import ru.milkyway.plugmanreloaded.update.input.UserCatalogWriter;
import ru.milkyway.plugmanreloaded.update.install.DownloadClient;
import ru.milkyway.plugmanreloaded.utils.HashUtil;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PluginDownloader {

    private final PlugManReloaded plugin;
    private final ServerProfile serverProfile;
    private final String userAgent;

    public record StageFailure(DownloadStatus outcome, String detail) {}

    public record StageAttempt(@Nullable StagedItem item, @Nullable StageFailure failure) {
        public @Nullable File file() {
            return item != null ? item.stagedPath().toFile() : null;
        }

        static StageAttempt success(StagedItem item) {
            return new StageAttempt(item, null);
        }

        static StageAttempt failed(DownloadStatus outcome, String detail) {
            return new StageAttempt(null, new StageFailure(outcome, detail));
        }
    }

    public record StagedItem(
            Path stagedPath,
            String declaredName,
            String version,
            String sourceId,
            String projectRef,
            String pageUrl,
            boolean requiresRestart,
            SearchResultEntry sourceEntry
    ) {}

    public PluginDownloader(PlugManReloaded plugin, ServerProfile serverProfile, String userAgent) {
        this.plugin = plugin;
        this.serverProfile = serverProfile;
        this.userAgent = userAgent;
    }

    public void executeInstallTransaction(
            SearchResultEntry targetEntry,
            List<SearchResultEntry> dependencyEntries,
            Consumer<DownloadResult> callback
    ) {
        UUID txId = UUID.randomUUID();
        Path stagingDir = plugin.getDataFolder().getParentFile().toPath()
                .resolve(".plugmanreloaded-tmp")
                .resolve("tx_" + txId);

        TaskScheduler.runAsync(plugin, () -> {
            try {
                Files.createDirectories(stagingDir);

                List<SearchResultEntry> allEntries = new ArrayList<>();
                if (dependencyEntries != null) {
                    allEntries.addAll(dependencyEntries);
                }
                allEntries.add(targetEntry);

                List<StagedItem> stagedItems = new ArrayList<>();

                for (SearchResultEntry entry : allEntries) {
                    StageAttempt attempt = stageAndValidate(entry, stagingDir);
                    if (attempt.item() == null) {
                        StageFailure failure = attempt.failure() != null
                                ? attempt.failure()
                                : new StageFailure(DownloadStatus.DOWNLOAD_FAILED, "actions.download.details.stage-failed");
                        cleanupQuietly(stagingDir);
                        TaskScheduler.runSync(plugin, () -> callback.accept(
                                DownloadResult.failed(failure.outcome(), entry.title(), entry.sourceId(), failure.detail())
                        ));
                        return;
                    }
                    stagedItems.add(attempt.item());
                }

                TaskScheduler.runSync(plugin, () -> {
                    DownloadResult result = commitAndActivate(stagedItems);
                    cleanupQuietly(stagingDir);
                    callback.accept(result);
                });

            } catch (Exception | LinkageError t) {
                cleanupQuietly(stagingDir);
                Log.error("plugindownloader.transaction-error", t, "error", t.getMessage());
                TaskScheduler.runSync(plugin, () -> callback.accept(
                        DownloadResult.failed(DownloadStatus.ACTIVATION_FAILED, targetEntry.title(), targetEntry.sourceId(), t.getMessage())
                ));
            }
        });
    }

    public StageAttempt stageForInspection(@Nullable SearchResultEntry entry, Path inspectionDir) {
        if (entry == null || inspectionDir == null) {
            return StageAttempt.failed(DownloadStatus.DOWNLOAD_FAILED, "actions.download.details.stage-failed");
        }
        try {
            Files.createDirectories(inspectionDir);
        } catch (Exception | LinkageError t) {
            Log.debug("plugindownloader.catalog-create-failed", t);
            return StageAttempt.failed(DownloadStatus.DOWNLOAD_FAILED, t.getMessage());
        }
        return stageAndValidate(entry, inspectionDir);
    }

    public @Nullable File stageForInspectionFile(@Nullable SearchResultEntry entry, Path inspectionDir) {
        return stageForInspection(entry, inspectionDir).file();
    }

    public void cleanupInspectionDir(Path dir) {
        cleanupQuietly(dir);
    }

    private StageAttempt stageAndValidate(SearchResultEntry entry, Path stagingDir) {
        try {
            DownloadResolution resolution = resolveDownloadInfo(entry);
            ResolvedDownloadInfo info = resolution.info();
            if (info == null || info.downloadUrl() == null || info.downloadUrl().isBlank()) {
                Log.debug("plugindownloader.direct-link-failed", "title", entry.title());
                String detail = resolution.failureDetail() != null
                        ? resolution.failureDetail()
                        : "actions.download.details.no-direct-link";
                return StageAttempt.failed(DownloadStatus.DOWNLOAD_FAILED, detail);
            }

            String safeName = (info.fileName() != null && !info.fileName().isBlank())
                    ? info.fileName().replaceAll("[^A-Za-z0-9._-]", "_")
                    : entry.projectId().replaceAll("[^A-Za-z0-9._-]", "_") + ".jar";

            Path targetStaged = stagingDir.resolve(safeName + ".tmp");

            DownloadClient.Downloaded downloaded = DownloadClient.download(info.downloadUrl(), targetStaged, userAgent);
            if (downloaded == null || !Files.exists(targetStaged)) {
                return StageAttempt.failed(DownloadStatus.DOWNLOAD_FAILED, "actions.download.details.network");
            }

            if (!validateMagicBytes(targetStaged)) {
                Files.deleteIfExists(targetStaged);
                return StageAttempt.failed(DownloadStatus.INVALID_PLUGIN, "actions.download.details.not-a-jar");
            }

            if (info.sha512() != null && !info.sha512().isBlank()) {
                String calcSha512 = calculateHash(targetStaged, "SHA-512");
                if (!info.sha512().equalsIgnoreCase(calcSha512)) {
                    Files.deleteIfExists(targetStaged);
                    return StageAttempt.failed(DownloadStatus.HASH_MISMATCH, "actions.download.details.sha512-mismatch");
                }
            } else if (info.sha256() != null && !info.sha256().isBlank()) {
                if (!info.sha256().equalsIgnoreCase(downloaded.sha256())) {
                    Files.deleteIfExists(targetStaged);
                    return StageAttempt.failed(DownloadStatus.HASH_MISMATCH, "actions.download.details.sha256-mismatch");
                }
            }

            File stagedFile = targetStaged.toFile();
            JarValidator.PreFlightReport report = JarValidator.validatePreFlight(stagedFile, null, false);
            if (!report.isValid()) {
                DownloadStatus outcome = switch (report.status()) {
                    case INCOMPATIBLE_JAVA -> DownloadStatus.INCOMPATIBLE_JAVA;
                    case NO_DESCRIPTOR -> DownloadStatus.INVALID_MANIFEST;
                    default -> DownloadStatus.INVALID_MANIFEST;
                };
                Files.deleteIfExists(targetStaged);
                return StageAttempt.failed(outcome, report.errorMessage());
            }

            String declaredName = report.declaredName() != null ? report.declaredName() : entry.title();
            boolean requiresRestart = report.hasBootstrapper()
                    || (report.isPaperPlugin() && PlatformDetector.isModernPaper());
            String ver = info.versionNumber() != null ? info.versionNumber() : (report.declaredVersion() != null ? report.declaredVersion() : "1.0");

            return StageAttempt.success(new StagedItem(targetStaged, declaredName, ver, entry.sourceId(), entry.projectId(), entry.url(), requiresRestart, entry));
        } catch (Exception | LinkageError t) {
            Log.debug("plugindownloader.stage-validate-failed", t, "title", entry.title());
            return StageAttempt.failed(DownloadStatus.DOWNLOAD_FAILED, t.getMessage());
        }
    }

    private record Progress(List<String> installed, List<Plugin> loaded, List<File> created) {

        static Progress empty() {
            return new Progress(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private DownloadResult commitAndActivate(List<StagedItem> stagedItems) {
        if (stagedItems.isEmpty()) {
            return DownloadResult.success("", "", "", List.of());
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        Progress progress = Progress.empty();
        boolean anyRequiresRestart = false;

        for (StagedItem item : stagedItems) {
            File targetFile = resolveTargetFile(item, pluginsDir);

            DownloadResult failure = item.requiresRestart()
                    ? stageForRestart(item, targetFile, pluginsDir, progress)
                    : installNow(item, targetFile, progress);
            if (failure != null) {
                return failure;
            }

            anyRequiresRestart |= item.requiresRestart();
            progress.installed().add(item.declaredName());
            saveCatalogSource(item.declaredName(), item);
        }

        refreshAfterInstall();

        StagedItem target = stagedItems.get(stagedItems.size() - 1);
        return anyRequiresRestart
                ? DownloadResult.bootstrapper(target.declaredName(), target.version(), target.sourceId())
                : DownloadResult.success(target.declaredName(), target.version(), target.sourceId(), progress.installed());
    }

    private File resolveTargetFile(StagedItem item, File pluginsDir) {
        File existing = plugin.getPluginLifecycleManager().getJarIndex().find(item.declaredName());
        if (existing != null && existing.exists()) {
            PluginJarIndex.JarDescriptor desc =
                    plugin.getPluginLifecycleManager().getJarIndex().readDescriptor(existing);
            if (desc != null && desc.declaredName() != null
                    && desc.declaredName().equalsIgnoreCase(item.declaredName())) {
                return existing;
            }
        }
        return new File(pluginsDir, item.declaredName() + ".jar");
    }

    private @Nullable DownloadResult stageForRestart(StagedItem item, File targetFile, File pluginsDir, Progress progress) {
        File updateFolder = new File(pluginsDir, "update");
        if (!updateFolder.exists()) {
            updateFolder.mkdirs();
        }

        File updateTarget = new File(updateFolder, targetFile.getName());
        boolean existedBefore = updateTarget.exists();
        try {
            Files.move(item.stagedPath(), updateTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!existedBefore) {
                progress.created().add(updateTarget);
            }
            return null;
        } catch (Throwable t) {
            rollbackInstalled(progress.loaded(), progress.created());
            Log.warn("plugindownloader.update-folder-write-failed", t, "plugin", item.declaredName());
            return DownloadResult.failed(DownloadStatus.WRITE_FAILED, item.declaredName(), item.sourceId(),
                    "actions.download.details.update-folder-write-failed");
        }
    }

    private @Nullable DownloadResult installNow(StagedItem item, File targetFile, Progress progress) {
        plugin.getHotSwapManager().temporarilyIgnore(targetFile.getName(), 5000L);

        boolean existedBefore = targetFile.exists();
        try {
            Files.move(item.stagedPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!existedBefore) {
                progress.created().add(targetFile);
            }
        } catch (Throwable t) {
            rollbackInstalled(progress.loaded(), progress.created());
            Log.warn("plugindownloader.disk-write-failed", t, "plugin", item.declaredName());
            return DownloadResult.failed(DownloadStatus.WRITE_FAILED, item.declaredName(), item.sourceId(),
                    "actions.download.details.disk-write-failed");
        }

        if (!plugin.getPluginLifecycleManager().load(targetFile).success()) {
            boolean clean = rollbackInstalled(progress.loaded(), progress.created());
            return DownloadResult.failed(
                    clean ? DownloadStatus.ROLLED_BACK : DownloadStatus.ACTIVATION_FAILED,
                    item.declaredName(), item.sourceId(),
                    clean ? "actions.download.details.load-failed-rolled-back"
                          : "actions.download.details.load-failed-dirty");
        }

        Plugin loaded = plugin.getPluginLifecycleManager().getPlugin(item.declaredName());
        if (loaded != null) {
            progress.loaded().add(loaded);
        }
        return null;
    }

    private void refreshAfterInstall() {
        try {
            plugin.getPluginLifecycleManager().getBrigadierManager().syncCommands();
            plugin.getPluginLifecycleManager().getJarIndex().invalidate();
            plugin.getUpdateService().reload();
        } catch (Throwable t) {
            Log.debug("plugindownloader.post-install-refresh-failed", t);
        }
    }

    private boolean rollbackInstalled(List<Plugin> newlyLoaded, List<File> newlyCreated) {
        boolean clean = true;
        Collections.reverse(newlyLoaded);
        for (Plugin p : newlyLoaded) {
            try {
                plugin.getPluginLifecycleManager().unload(p);
            } catch (Throwable t) {
                clean = false;
                Log.warn("plugindownloader.rollback-unload-failed", t, "plugin", p.getName());
            }
        }
        for (File f : newlyCreated) {
            try {
                Files.deleteIfExists(f.toPath());
            } catch (Throwable t) {
                clean = false;
                Log.warn("plugindownloader.rollback-delete-failed", t, "file", f.getName());
            }
        }
        return clean;
    }

    private void saveCatalogSource(String pluginName, StagedItem item) {
        try {
            File customSourcesFile = SourceCatalog.resolveFile(plugin);
            String mainClass = null;
            Plugin p = plugin.getPluginLifecycleManager().getPlugin(pluginName);
            if (p != null) {
                mainClass = p.getDescription().getMain();
            } else {
                File targetFile = plugin.getPluginLifecycleManager().getJarIndex().find(pluginName);
                if (targetFile != null && targetFile.exists()) {
                    try (JarFile jar = new JarFile(targetFile)) {
                        JarEntry entry = jar.getJarEntry("plugin.yml");
                        if (entry == null) entry = jar.getJarEntry("paper-plugin.yml");
                        if (entry != null) {
                            try (Reader r = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                                mainClass = YamlConfiguration.loadConfiguration(r).getString("main");
                            }
                        }
                    }
                }
            }

            SourceCatalog.CatalogSource cat = new SourceCatalog.CatalogSource(
                    item.sourceId(),
                    item.projectRef(),
                    item.pageUrl(),
                    Collections.emptyMap()
            );
            String language = plugin.getConfigManager().getMainConfig().getLanguage();
            UserCatalogWriter.write(customSourcesFile, pluginName, mainClass, cat, language);
        } catch (Throwable t) {
            Log.warn("plugindownloader.source-write-failed", t, "plugin", pluginName);
        }
    }

    private record ResolvedDownloadInfo(
            String downloadUrl,
            String fileName,
            String versionNumber,
            String sha512,
            String sha256
    ) {}

    private record DownloadResolution(
            @Nullable ResolvedDownloadInfo info,
            @Nullable String failureDetail
    ) {
        static DownloadResolution of(ResolvedDownloadInfo info) {
            return new DownloadResolution(info, null);
        }

        static DownloadResolution failed(String detail) {
            return new DownloadResolution(null, detail);
        }
    }

    private DownloadResolution resolveDownloadInfo(SearchResultEntry entry) {
        if (entry.downloadUrl() != null && !entry.downloadUrl().isBlank()) {
            return DownloadResolution.of(new ResolvedDownloadInfo(entry.downloadUrl(), entry.fileName(), entry.version(), entry.sha512(), entry.sha256()));
        }

        String src = entry.sourceId().toLowerCase(Locale.ROOT);
        switch (src) {
            case "modrinth" -> {
                return resolveModrinthDownload(entry.projectId());
            }
            case "hangar" -> {
                return resolveHangarDownload(entry.projectId());
            }
            case "spigot", "spigotmc" -> {
                String downloadUrl = "https://api.spiget.org/v2/resources/" + entry.projectId() + "/download";
                return DownloadResolution.of(new ResolvedDownloadInfo(downloadUrl, entry.title() + ".jar", "1.0", null, null));
            }
            case "github" -> {
                return resolveGithubDownload(entry.projectId());
            }
            default -> {
                return DownloadResolution.of(new ResolvedDownloadInfo(entry.url(), entry.title() + ".jar", "1.0", null, null));
            }
        }
    }

    private DownloadResolution resolveModrinthDownload(String slug) {
        try {
            String url = "https://api.modrinth.com/v2/project/" + slug + "/version";
            HttpJson.Response resp = HttpJson.get(url);
            if (!resp.ok() || !resp.body().isJsonArray()) {
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            JsonArray array = resp.body().getAsJsonArray();
            String serverMc = serverProfile != null ? serverProfile.minecraftVersion() : null;
            Set<String> loaders = serverProfile != null ? serverProfile.loaders() : null;

            JsonObject bestVersion = null;
            int bestScore = -1;

            for (JsonElement e : array) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                if (!v.has("files") || !v.get("files").isJsonArray()) continue;

                int score = scoreModrinthVersion(v, serverMc, loaders);
                if (score > bestScore) {
                    bestScore = score;
                    bestVersion = v;
                }
            }

            if (bestVersion == null && array.size() > 0 && array.get(0).isJsonObject()) {
                bestVersion = array.get(0).getAsJsonObject();
            }

            if (bestVersion != null && bestVersion.has("files") && bestVersion.get("files").isJsonArray()) {
                String verNum = (bestVersion.has("version_number") && bestVersion.get("version_number").isJsonPrimitive())
                        ? bestVersion.get("version_number").getAsString()
                        : "1.0";
                JsonArray files = bestVersion.getAsJsonArray("files");

                for (JsonElement fe : files) {
                    if (!fe.isJsonObject()) continue;
                    JsonObject f = fe.getAsJsonObject();
                    String dlUrl = f.has("url") ? f.get("url").getAsString() : null;
                    String filename = f.has("filename") ? f.get("filename").getAsString() : null;

                    String sha512 = null;
                    if (f.has("hashes") && f.get("hashes").isJsonObject()) {
                        JsonObject h = f.getAsJsonObject("hashes");
                        if (h.has("sha512")) sha512 = h.get("sha512").getAsString();
                    }

                    if (dlUrl != null) {
                        return DownloadResolution.of(new ResolvedDownloadInfo(dlUrl, filename, verNum, sha512, null));
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("plugindownloader.modrinth-parse-failed", t);
        }
        return DownloadResolution.failed("actions.download.details.no-direct-link");
    }

    private int scoreModrinthVersion(JsonObject v, String serverMcVersion, Set<String> serverLoaders) {
        int score = 0;

        String verType = v.has("version_type") && v.get("version_type").isJsonPrimitive()
                ? v.get("version_type").getAsString().toLowerCase(Locale.ROOT)
                : "release";
        if ("release".equals(verType)) {
            score += 50;
        } else if ("beta".equals(verType)) {
            score += 20;
        }

        List<String> gv = new ArrayList<>();
        if (v.has("game_versions") && v.get("game_versions").isJsonArray()) {
            for (JsonElement ge : v.getAsJsonArray("game_versions")) {
                if (ge.isJsonPrimitive()) gv.add(ge.getAsString());
            }
        }

        if (serverMcVersion != null && !serverMcVersion.isBlank()) {
            if (gv.contains(serverMcVersion)) {
                score += 100;
            } else {
                String majorMinor = extractMajorMinor(serverMcVersion);
                if (gv.contains(majorMinor)) {
                    score += 80;
                } else {
                    boolean sameSeries = false;
                    for (String s : gv) {
                        if (extractMajorMinor(s).equalsIgnoreCase(majorMinor)) {
                            sameSeries = true;
                            break;
                        }
                    }
                    if (sameSeries) {
                        score += 60;
                    }
                }
            }
        } else {
            score += 50;
        }

        List<String> loaders = new ArrayList<>();
        if (v.has("loaders") && v.get("loaders").isJsonArray()) {
            for (JsonElement le : v.getAsJsonArray("loaders")) {
                if (le.isJsonPrimitive()) loaders.add(le.getAsString().toLowerCase(Locale.ROOT));
            }
        }

        if (serverLoaders != null && !serverLoaders.isEmpty()) {
            boolean loaderMatch = false;
            for (String sl : serverLoaders) {
                if (loaders.contains(sl.toLowerCase(Locale.ROOT))) {
                    loaderMatch = true;
                    break;
                }
            }
            if (loaderMatch) {
                score += 30;
            }
        } else {
            score += 10;
        }

        return score;
    }

    private static String extractMajorMinor(@Nullable String ver) {
        if (ver == null || ver.isBlank()) return "";
        String[] parts = ver.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return ver;
    }

    private DownloadResolution resolveHangarDownload(String projectRef) {
        try {
            String slug = projectRef.contains("/")
                    ? projectRef.substring(projectRef.indexOf('/') + 1)
                    : projectRef;
            String url = "https://hangar.papermc.io/api/v1/projects/" + HttpJson.encodePath(slug) + "/versions?limit=25";
            HttpJson.Response resp = HttpJson.get(url);
            if (!resp.ok() || !resp.body().isJsonObject()) {
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            JsonObject obj = resp.body().getAsJsonObject();
            if (!obj.has("result") || !obj.get("result").isJsonArray()) {
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            JsonArray res = obj.getAsJsonArray("result");
            if (res.size() == 0) {
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            JsonObject bestVersion = null;
            int bestScore = -1;
            String serverMc = serverProfile != null ? serverProfile.minecraftVersion() : null;

            for (JsonElement e : res) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                int score = scoreHangarVersion(v, serverMc);
                if (score > bestScore) {
                    bestScore = score;
                    bestVersion = v;
                }
            }

            if (bestVersion == null) {
                bestVersion = res.get(0).getAsJsonObject();
            }

            String verName = bestVersion.has("name") ? bestVersion.get("name").getAsString() : "1.0";

            if (bestVersion.has("downloads") && bestVersion.get("downloads").isJsonObject()) {
                JsonObject dls = bestVersion.getAsJsonObject("downloads");
                if (dls.has("PAPER") && dls.get("PAPER").isJsonObject()) {
                    JsonObject paperDl = dls.getAsJsonObject("PAPER");
                    String dlUrl = paperDl.has("downloadUrl") && !paperDl.get("downloadUrl").isJsonNull()
                            ? paperDl.get("downloadUrl").getAsString()
                            : (paperDl.has("externalUrl") && !paperDl.get("externalUrl").isJsonNull() ? paperDl.get("externalUrl").getAsString() : null);

                    String fileName = null;
                    String sha256 = null;
                    if (paperDl.has("fileInfo") && paperDl.get("fileInfo").isJsonObject()) {
                        JsonObject fi = paperDl.getAsJsonObject("fileInfo");
                        fileName = fi.has("name") ? fi.get("name").getAsString() : null;
                        sha256 = fi.has("sha256Hash") ? fi.get("sha256Hash").getAsString() : null;
                    }
                    if (dlUrl != null) {
                        return DownloadResolution.of(new ResolvedDownloadInfo(dlUrl, fileName, verName, null, sha256));
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("plugindownloader.hangar-parse-failed", t);
        }
        return DownloadResolution.failed("actions.download.details.no-direct-link");
    }

    private int scoreHangarVersion(JsonObject v, String serverMcVersion) {
        int score = 0;

        if (v.has("channel") && v.get("channel").isJsonObject()) {
            String ch = v.getAsJsonObject("channel").has("name") ? v.getAsJsonObject("channel").get("name").getAsString().toLowerCase(Locale.ROOT) : "";
            if ("release".equals(ch)) score += 50;
            else if ("beta".equals(ch)) score += 20;
        }

        if (v.has("platformDependencies") && v.get("platformDependencies").isJsonObject()) {
            JsonObject pd = v.getAsJsonObject("platformDependencies");
            if (pd.has("PAPER") && pd.get("PAPER").isJsonArray()) {
                List<String> deps = new ArrayList<>();
                for (JsonElement e : pd.getAsJsonArray("PAPER")) {
                    if (e.isJsonPrimitive()) deps.add(e.getAsString());
                }
                if (serverMcVersion != null && !serverMcVersion.isBlank()) {
                    if (deps.contains(serverMcVersion)) {
                        score += 100;
                    } else {
                        String majorMinor = extractMajorMinor(serverMcVersion);
                        if (deps.contains(majorMinor)) {
                            score += 80;
                        }
                    }
                } else {
                    score += 50;
                }
            }
        }
        return score;
    }

    private static final double MIN_GITHUB_ASSET_SIMILARITY = 0.55;

    private DownloadResolution resolveGithubDownload(String repo) {
        try {
            String token = plugin != null && plugin.getConfigManager() != null ? plugin.getConfigManager().getGithubToken() : null;
            String authorization = (token != null && !token.isBlank()) ? "Bearer " + token.trim() : null;

            String url = "https://api.github.com/repos/" + repo + "/releases/latest";
            HttpJson.Response resp = HttpJson.get(url, authorization);
            JsonObject release = null;
            if (resp.ok() && resp.body().isJsonObject()) {
                release = resp.body().getAsJsonObject();
            } else {
                String fallbackUrl = "https://api.github.com/repos/" + repo + "/releases?per_page=1";
                HttpJson.Response fallbackResp = HttpJson.get(fallbackUrl, authorization);
                if (fallbackResp.ok() && fallbackResp.body().isJsonArray()) {
                    JsonArray arr = fallbackResp.body().getAsJsonArray();
                    if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                        JsonObject candidate = arr.get(0).getAsJsonObject();
                        boolean draft = candidate.has("draft") && !candidate.get("draft").isJsonNull()
                                && candidate.get("draft").getAsBoolean();
                        boolean prerelease = candidate.has("prerelease") && !candidate.get("prerelease").isJsonNull()
                                && candidate.get("prerelease").getAsBoolean();
                        if (!draft && !prerelease) {
                            release = candidate;
                        }
                    }
                }
            }

            if (release == null) {
                return DownloadResolution.failed("actions.download.details.github-no-releases");
            }

            String tagName = release.has("tag_name") ? release.get("tag_name").getAsString() : "1.0";

            if (release.has("assets") && release.get("assets").isJsonArray()) {
                JsonArray assets = release.getAsJsonArray("assets");
                JsonObject bestAsset = null;
                double bestScore = -1.0;
                String repoName = repo.contains("/") ? repo.substring(repo.lastIndexOf('/') + 1) : repo;

                for (JsonElement ae : assets) {
                    if (!ae.isJsonObject()) continue;
                    JsonObject asset = ae.getAsJsonObject();
                    String name = asset.has("name") ? asset.get("name").getAsString() : "";
                    if (!name.endsWith(".jar") || AssetUtil.isNonRuntimeArtifact(name)
                            || AssetUtil.isCompanion(repoName, name)) {
                        continue;
                    }
                    double score = AssetUtil.similarity(repoName, name) + AssetUtil.platformBonus(name, serverProfile);
                    if (score > bestScore) {
                        bestScore = score;
                        bestAsset = asset;
                    }
                }

                if (bestAsset != null && bestScore >= MIN_GITHUB_ASSET_SIMILARITY) {
                    String name = bestAsset.has("name") ? bestAsset.get("name").getAsString() : "";
                    String dlUrl = bestAsset.has("browser_download_url") ? bestAsset.get("browser_download_url").getAsString() : "";
                    if (!dlUrl.isBlank()) {
                        return DownloadResolution.of(new ResolvedDownloadInfo(dlUrl, name, tagName, null, digest(bestAsset)));
                    }
                } else {
                    return DownloadResolution.failed("actions.download.details.github-no-jar");
                }
            } else {
                return DownloadResolution.failed("actions.download.details.github-no-assets");
            }
        } catch (Throwable t) {
            Log.debug("plugindownloader.github-parse-failed", t, "repo", repo);
        }
        return DownloadResolution.failed("actions.download.details.no-direct-link");
    }

    private static @Nullable String digest(JsonObject asset) {
        if (!asset.has("digest") || asset.get("digest").isJsonNull()) return null;
        String raw = asset.get("digest").getAsString();
        int colon = raw.indexOf(':');
        return colon >= 0 ? raw.substring(colon + 1) : raw;
    }

    private static boolean validateMagicBytes(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] header = is.readNBytes(4);
            return header.length == 4 && header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
        } catch (Throwable t) {
            return false;
        }
    }

    private static @Nullable String calculateHash(Path path, String algorithm) {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HashUtil.toHex(md.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void cleanupQuietly(@Nullable Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Throwable t) {
                                Log.debug("plugindownloader.temp-file-delete-failed", t, "file", String.valueOf(p));
                            }
                        });
            }
        } catch (Throwable t) {
            Log.debug("plugindownloader.staging-cleanup-failed", t);
        }
    }
}

