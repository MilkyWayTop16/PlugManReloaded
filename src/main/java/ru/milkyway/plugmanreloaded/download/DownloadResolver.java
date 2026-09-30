package ru.milkyway.plugmanreloaded.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.download.DownloadModels.SearchResultEntry;
import ru.milkyway.plugmanreloaded.update.HttpJson;
import ru.milkyway.plugmanreloaded.update.PluginMatcher;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.VersionCompare;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

class DownloadResolver {

    private static final double MIN_GITHUB_ASSET_SIMILARITY = 0.55;

    private final PlugManReloaded plugin;
    private final ServerProfile serverProfile;

    DownloadResolver(PlugManReloaded plugin, ServerProfile serverProfile) {
        this.plugin = plugin;
        this.serverProfile = serverProfile;
    }

    DownloadResolution resolve(SearchResultEntry entry) {
        if (entry.downloadUrl() != null && !entry.downloadUrl().isBlank()) {
            String fileName = entry.fileName() != null && !entry.fileName().isBlank()
                    ? entry.fileName()
                    : sanitizeFileName(entry.title());
            return DownloadResolution.of(new ResolvedDownload(
                    entry.downloadUrl(), fileName, entry.version(), entry.sha512(), entry.sha256()));
        }

        if (!entry.directDownloadable()) {
            return DownloadResolution.failed("actions.download.details.no-direct-link");
        }

        String initialVersion = entry.version() != null && !entry.version().isBlank() ? entry.version() : null;
        return switch (entry.sourceId().toLowerCase(Locale.ROOT)) {
            case "modrinth" -> resolveModrinthDownload(entry.projectId());
            case "hangar" -> resolveHangarDownload(entry.projectId());
            case "spigot", "spigotmc" -> DownloadResolution.of(new ResolvedDownload(
                    "https://api.spiget.org/v2/resources/" + entry.projectId() + "/download",
                    sanitizeFileName(entry.title()), initialVersion, null, null));
            case "github" -> resolveGithubDownload(entry.projectId());
            default -> DownloadResolution.of(new ResolvedDownload(entry.url(), sanitizeFileName(entry.title()), initialVersion, null, null));
        };
    }

    private static String sanitizeFileName(@Nullable String title) {
        if (title == null || title.isBlank()) {
            return "plugin.jar";
        }
        String clean = title.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isBlank() ? "plugin.jar" : clean + ".jar";
    }

    private DownloadResolution resolveModrinthDownload(String projectId) {
        try {
            HttpJson.Response response = HttpJson.get("https://api.modrinth.com/v2/project/" + HttpJson.encodePath(projectId) + "/version");
            if (!response.ok() || !response.body().isJsonArray()) {
                if (response.rateLimited()) {
                    return DownloadResolution.failed("actions.download.details.rate-limited");
                }
                if (response.transportFailure()) {
                    return DownloadResolution.failed("actions.download.details.network");
                }
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            String minecraftVersion = serverProfile != null ? serverProfile.minecraftVersion() : null;
            Set<String> loaders = serverProfile != null ? serverProfile.loaders() : Set.of();

            java.util.List<JsonObject> candidates = new java.util.ArrayList<>();
            for (JsonElement element : response.body().getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject version = element.getAsJsonObject();
                if (!isModrinthVersionCompatible(version, minecraftVersion, loaders)) {
                    continue;
                }
                candidates.add(version);
            }

            if (candidates.isEmpty()) {
                if (response.body().getAsJsonArray().size() > 0) {
                    return DownloadResolution.failed("actions.download.details.no-compatible-version");
                }
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            candidates.sort((a, b) -> {
                int scoreA = scoreModrinthVersion(a, minecraftVersion, loaders);
                int scoreB = scoreModrinthVersion(b, minecraftVersion, loaders);
                return Integer.compare(scoreB, scoreA);
            });

            java.util.List<ResolvedDownload> resolved = new java.util.ArrayList<>();
            for (JsonObject selectedVersion : candidates) {
                if (resolved.size() >= 6) {
                    break;
                }
                if (!selectedVersion.has("files") || !selectedVersion.get("files").isJsonArray()) {
                    continue;
                }
                JsonObject file = selectModrinthRuntimeFile(selectedVersion.getAsJsonArray("files"));
                if (file == null || !file.has("url") || file.get("url").isJsonNull()) {
                    continue;
                }
                String version = selectedVersion.has("version_number") && selectedVersion.get("version_number").isJsonPrimitive()
                        ? selectedVersion.get("version_number").getAsString() : null;
                String fileName = file.has("filename") && !file.get("filename").isJsonNull()
                        ? file.get("filename").getAsString() : null;
                String sha512 = null;
                String sha256 = null;
                if (file.has("hashes") && file.get("hashes").isJsonObject()) {
                    JsonObject hashes = file.getAsJsonObject("hashes");
                    sha512 = stringOrNull(hashes, "sha512");
                    sha256 = stringOrNull(hashes, "sha256");
                }
                resolved.add(new ResolvedDownload(file.get("url").getAsString(), fileName, version, sha512, sha256));
            }

            return DownloadResolution.ofCandidates(resolved);
        } catch (Exception e) {
            Log.debug("plugindownloader.modrinth-parse-failed", e);
            return DownloadResolution.failed("actions.download.details.no-direct-link");
        }
    }

    private DownloadResolution resolveHangarDownload(String projectRef) {
        try {
            String project = projectRef.contains("/") ? projectRef.substring(projectRef.indexOf('/') + 1) : projectRef;
            HttpJson.Response response = HttpJson.get(
                    "https://hangar.papermc.io/api/v1/projects/" + HttpJson.encodePath(project) + "/versions?limit=25");
            if (!response.ok() || !response.body().isJsonObject()) {
                if (response.rateLimited()) {
                    return DownloadResolution.failed("actions.download.details.rate-limited");
                }
                if (response.transportFailure()) {
                    return DownloadResolution.failed("actions.download.details.network");
                }
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }
            JsonObject payload = response.body().getAsJsonObject();
            if (!payload.has("result") || !payload.get("result").isJsonArray()) {
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            String minecraftVersion = serverProfile != null ? serverProfile.minecraftVersion() : null;
            java.util.List<JsonObject> candidates = new java.util.ArrayList<>();
            for (JsonElement element : payload.getAsJsonArray("result")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject version = element.getAsJsonObject();
                if (!isHangarVersionCompatible(version, minecraftVersion)) {
                    continue;
                }
                candidates.add(version);
            }

            if (candidates.isEmpty()) {
                if (payload.getAsJsonArray("result").size() > 0) {
                    return DownloadResolution.failed("actions.download.details.no-compatible-version");
                }
                return DownloadResolution.failed("actions.download.details.no-direct-link");
            }

            candidates.sort((a, b) -> {
                int scoreA = scoreHangarVersion(a, minecraftVersion);
                int scoreB = scoreHangarVersion(b, minecraftVersion);
                return Integer.compare(scoreB, scoreA);
            });

            java.util.List<ResolvedDownload> resolved = new java.util.ArrayList<>();
            for (JsonObject selectedVersion : candidates) {
                if (resolved.size() >= 6) {
                    break;
                }
                if (!selectedVersion.has("downloads") || !selectedVersion.get("downloads").isJsonObject()) {
                    continue;
                }
                JsonObject downloads = selectedVersion.getAsJsonObject("downloads");
                if (!downloads.has("PAPER") || !downloads.get("PAPER").isJsonObject()) {
                    continue;
                }
                JsonObject paper = downloads.getAsJsonObject("PAPER");
                String downloadUrl = stringOrNull(paper, "downloadUrl");
                if (downloadUrl == null) {
                    downloadUrl = stringOrNull(paper, "externalUrl");
                }
                if (downloadUrl == null) {
                    continue;
                }

                JsonObject fileInfo = paper.has("fileInfo") && paper.get("fileInfo").isJsonObject()
                        ? paper.getAsJsonObject("fileInfo") : null;
                String fileName = fileInfo != null ? stringOrNull(fileInfo, "name") : null;
                String sha256 = fileInfo != null ? stringOrNull(fileInfo, "sha256Hash") : null;
                resolved.add(new ResolvedDownload(downloadUrl, fileName, stringOrNull(selectedVersion, "name"), null, sha256));
            }
            return DownloadResolution.ofCandidates(resolved);
        } catch (Exception e) {
            Log.debug("plugindownloader.hangar-parse-failed", e);
            return DownloadResolution.failed("actions.download.details.no-direct-link");
        }
    }

    private DownloadResolution resolveGithubDownload(String repo) {
        try {
            String token = plugin != null && plugin.getConfigManager() != null ? plugin.getConfigManager().getGithubToken() : null;
            String authorization = token != null && !token.isBlank() ? "Bearer " + token.trim() : null;
            HttpJson.Response response = HttpJson.get("https://api.github.com/repos/" + repo + "/releases?per_page=10", authorization);
            if (!response.ok() || !response.body().isJsonArray()) {
                if (response.rateLimited()) {
                    return DownloadResolution.failed("actions.download.details.rate-limited");
                }
                if (response.transportFailure()) {
                    return DownloadResolution.failed("actions.download.details.network");
                }
                return DownloadResolution.failed("actions.download.details.github-no-releases");
            }

            java.util.List<JsonObject> releases = new java.util.ArrayList<>();
            for (JsonElement candidate : response.body().getAsJsonArray()) {
                if (!candidate.isJsonObject()) {
                    continue;
                }
                JsonObject release = candidate.getAsJsonObject();
                if (release.has("draft") && !release.get("draft").isJsonNull() && release.get("draft").getAsBoolean()) {
                    continue;
                }
                if (release.has("prerelease") && !release.get("prerelease").isJsonNull() && release.get("prerelease").getAsBoolean()) {
                    continue;
                }
                releases.add(release);
            }

            if (releases.isEmpty()) {
                return DownloadResolution.failed("actions.download.details.github-no-releases");
            }

            String repoName = repo.contains("/") ? repo.substring(repo.lastIndexOf('/') + 1) : repo;
            java.util.List<ResolvedDownload> resolved = new java.util.ArrayList<>();
            for (JsonObject release : releases) {
                if (resolved.size() >= 6) {
                    break;
                }
                if (!release.has("assets") || !release.get("assets").isJsonArray()) {
                    continue;
                }
                JsonObject bestAsset = null;
                double bestScore = -1.0;
                for (JsonElement element : release.getAsJsonArray("assets")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject asset = element.getAsJsonObject();
                    String name = stringOrNull(asset, "name");
                    if (name == null || !name.endsWith(".jar") || PluginMatcher.isNonRuntimeArtifact(name)
                            || PluginMatcher.isCompanion(repoName, name)) {
                        continue;
                    }
                    double score = PluginMatcher.similarity(repoName, name) + PluginMatcher.platformBonus(name, serverProfile);
                    if (score > bestScore) {
                        bestScore = score;
                        bestAsset = asset;
                    }
                }
                if (bestAsset == null || bestScore < MIN_GITHUB_ASSET_SIMILARITY) {
                    continue;
                }

                String downloadUrl = stringOrNull(bestAsset, "browser_download_url");
                if (downloadUrl == null) {
                    continue;
                }
                resolved.add(new ResolvedDownload(downloadUrl, stringOrNull(bestAsset, "name"),
                        stringOrNull(release, "tag_name"), null, digest(bestAsset)));
            }
            if (resolved.isEmpty()) {
                return DownloadResolution.failed("actions.download.details.github-no-jar");
            }
            return DownloadResolution.ofCandidates(resolved);
        } catch (Exception e) {
            Log.debug("plugindownloader.github-parse-failed", e, "repo", repo);
            return DownloadResolution.failed("actions.download.details.no-direct-link");
        }
    }

    private static int scoreModrinthVersion(JsonObject version, @Nullable String minecraftVersion, Set<String> serverLoaders) {
        int score = channelScore(version, "version_type");
        Set<String> gameVersions = jsonStringSet(version, "game_versions");
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            score += 50;
        } else if (gameVersions.contains(minecraftVersion.toLowerCase(Locale.ROOT))) {
            score += 100;
        } else if (gameVersions.contains(extractMajorMinor(minecraftVersion).toLowerCase(Locale.ROOT))) {
            score += 80;
        }
        if (serverLoaders == null || serverLoaders.isEmpty()) {
            return score + 10;
        }
        Set<String> loaders = jsonStringSet(version, "loaders");
        for (String loader : serverLoaders) {
            if (loader != null && loaders.contains(loader.toLowerCase(Locale.ROOT))) {
                return score + 30;
            }
        }
        return score;
    }

    private static int scoreHangarVersion(JsonObject version, @Nullable String minecraftVersion) {
        int score = 0;
        if (version.has("channel") && version.get("channel").isJsonObject()) {
            score = channelScore(version.getAsJsonObject("channel"), "name");
        }
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return score + 50;
        }
        JsonObject dependencies = version.has("platformDependencies") && version.get("platformDependencies").isJsonObject()
                ? version.getAsJsonObject("platformDependencies") : null;
        if (dependencies == null || !dependencies.has("PAPER") || !dependencies.get("PAPER").isJsonArray()) {
            return score;
        }
        Set<String> versions = new LinkedHashSet<>();
        for (JsonElement element : dependencies.getAsJsonArray("PAPER")) {
            if (element.isJsonPrimitive()) {
                versions.add(element.getAsString());
            }
        }
        if (versions.contains(minecraftVersion)) {
            return score + 100;
        }
        if (versions.contains(extractMajorMinor(minecraftVersion))) {
            return score + 80;
        }
        return score;
    }

    private static int channelScore(JsonObject object, String field) {
        String channel = stringOrNull(object, field);
        if ("release".equalsIgnoreCase(channel)) {
            return 50;
        }
        return "beta".equalsIgnoreCase(channel) ? 20 : 0;
    }

    static @Nullable JsonObject selectModrinthRuntimeFile(JsonArray files) {
        JsonObject firstRuntime = null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            String name = stringOrNull(file, "filename");
            if (name == null || PluginMatcher.isNonRuntimeArtifact(name)) {
                continue;
            }
            if (firstRuntime == null) {
                firstRuntime = file;
            }
            if (file.has("primary") && !file.get("primary").isJsonNull() && file.get("primary").getAsBoolean()) {
                return file;
            }
        }
        return firstRuntime;
    }

    static boolean isModrinthVersionCompatible(JsonObject version, @Nullable String minecraftVersion,
                                               @Nullable Set<String> serverLoaders) {
        Set<String> gameVersions = jsonStringSet(version, "game_versions");
        if (minecraftVersion != null && !minecraftVersion.isBlank() && !gameVersions.isEmpty()
                && !VersionCompare.supportsGameVersion(gameVersions, minecraftVersion)) {
            return false;
        }
        Set<String> loaders = jsonStringSet(version, "loaders");
        if (serverLoaders == null || serverLoaders.isEmpty() || loaders.isEmpty()) {
            return true;
        }
        for (String loader : serverLoaders) {
            if (loader != null && loaders.contains(loader.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean isHangarVersionCompatible(JsonObject version, @Nullable String minecraftVersion) {
        if (!version.has("downloads") || !version.get("downloads").isJsonObject()
                || !version.getAsJsonObject("downloads").has("PAPER")) {
            return false;
        }
        if (minecraftVersion == null || minecraftVersion.isBlank()
                || !version.has("platformDependencies") || !version.get("platformDependencies").isJsonObject()) {
            return true;
        }
        JsonObject dependencies = version.getAsJsonObject("platformDependencies");
        if (!dependencies.has("PAPER") || !dependencies.get("PAPER").isJsonArray()) {
            return true;
        }
        Set<String> gameVersions = new LinkedHashSet<>();
        for (JsonElement element : dependencies.getAsJsonArray("PAPER")) {
            if (element.isJsonPrimitive()) {
                gameVersions.add(element.getAsString());
            }
        }
        return gameVersions.isEmpty() || VersionCompare.supportsGameVersion(gameVersions, minecraftVersion);
    }

    private static Set<String> jsonStringSet(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonElement element : object.getAsJsonArray(field)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(values);
    }

    private static String extractMajorMinor(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    private static @Nullable String digest(JsonObject asset) {
        String raw = stringOrNull(asset, "digest");
        if (raw == null) {
            return null;
        }
        int separator = raw.indexOf(':');
        return separator >= 0 ? raw.substring(separator + 1) : raw;
    }

    private static @Nullable String stringOrNull(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
    }

    record ResolvedDownload(String downloadUrl, String fileName, String versionNumber, String sha512, String sha256) {}

    record DownloadResolution(@Nullable ResolvedDownload info, @Nullable java.util.List<ResolvedDownload> candidates, @Nullable String failureDetail) {
        static DownloadResolution of(ResolvedDownload info) {
            return new DownloadResolution(info, java.util.List.of(info), null);
        }

        static DownloadResolution ofCandidates(java.util.List<ResolvedDownload> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                return failed("actions.download.details.no-direct-link");
            }
            return new DownloadResolution(candidates.get(0), candidates, null);
        }

        static DownloadResolution failed(String detail) {
            return new DownloadResolution(null, java.util.List.of(), detail);
        }
    }
}

