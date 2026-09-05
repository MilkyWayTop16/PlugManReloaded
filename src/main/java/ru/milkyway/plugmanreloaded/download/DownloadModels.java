package ru.milkyway.plugmanreloaded.download;

import java.util.Collections;
import java.util.List;

public final class DownloadModels {

    private DownloadModels() {}

    public enum DownloadStatus {
        INSTALLED,
        BOOTSTRAPPER_RESTART_REQUIRED,
        DEPENDENCIES_REQUIRED,
        CIRCULAR_DEPENDENCIES,
        DOWNLOAD_FAILED,
        HASH_MISMATCH,
        INVALID_PLUGIN,
        INVALID_MANIFEST,
        INCOMPATIBLE_JAVA,
        ACTIVATION_FAILED,
        ROLLED_BACK,
        WRITE_FAILED,
        RATE_LIMITED,
        LOCKED
    }

    public record DownloadResult(
            DownloadStatus outcome,
            String pluginName,
            String version,
            String sourceId,
            String message,
            List<String> installedDependencies
    ) {
        public DownloadResult {
            if (installedDependencies == null) installedDependencies = Collections.emptyList();
        }

        public static DownloadResult success(String pluginName, String version, String sourceId, List<String> installedDeps) {
            return new DownloadResult(DownloadStatus.INSTALLED, pluginName, version, sourceId, "", installedDeps);
        }

        public static DownloadResult bootstrapper(String pluginName, String version, String sourceId) {
            return new DownloadResult(DownloadStatus.BOOTSTRAPPER_RESTART_REQUIRED, pluginName, version, sourceId, "", Collections.emptyList());
        }

        public static DownloadResult failed(DownloadStatus outcome, String pluginName, String message) {
            return failed(outcome, pluginName, null, message);
        }

        public static DownloadResult failed(DownloadStatus outcome, String pluginName, String sourceId, String message) {
            return new DownloadResult(outcome, pluginName, null, sourceId, message, Collections.emptyList());
        }

        public boolean success() {
            return outcome == DownloadStatus.INSTALLED || outcome == DownloadStatus.BOOTSTRAPPER_RESTART_REQUIRED;
        }
    }

    public record DependencyTree(
            String targetPluginName,
            SearchResultEntry targetEntry,
            List<SearchResultEntry> requiredDependencies,
            List<SearchResultEntry> optionalDependencies,
            List<String> alreadySatisfied,
            List<String> existingDisabledToEnable,
            List<String> existingUnloadedToLoad,
            List<String> unresolvableDependencies,
            boolean hasCycles,
            String cycleDetails
    ) {
        public DependencyTree {
            if (requiredDependencies == null) requiredDependencies = Collections.emptyList();
            if (optionalDependencies == null) optionalDependencies = Collections.emptyList();
            if (alreadySatisfied == null) alreadySatisfied = Collections.emptyList();
            if (existingDisabledToEnable == null) existingDisabledToEnable = Collections.emptyList();
            if (existingUnloadedToLoad == null) existingUnloadedToLoad = Collections.emptyList();
            if (unresolvableDependencies == null) unresolvableDependencies = Collections.emptyList();
        }

        public boolean hasMissing() {
            return !requiredDependencies.isEmpty() || !existingDisabledToEnable.isEmpty() || !existingUnloadedToLoad.isEmpty();
        }

        public boolean isFullyResolvable() {
            return !hasCycles && unresolvableDependencies.isEmpty();
        }
    }

    public record SearchResultEntry(
            String sourceId,
            String projectId,
            String title,
            String author,
            String version,
            String description,
            String url,
            String downloadUrl,
            long downloads,
            int stars,
            double score,
            List<String> gameVersions,
            List<String> loaders,
            List<String> dependencies,
            String sha512,
            String sha256,
            String fileName,
            boolean premium,
            boolean directDownloadable
    ) {
        public SearchResultEntry {
            if (gameVersions == null) gameVersions = Collections.emptyList();
            if (loaders == null) loaders = Collections.emptyList();
            if (dependencies == null) dependencies = Collections.emptyList();
        }

        public SearchResultEntry withScore(double newScore) {
            return new SearchResultEntry(
                    sourceId, projectId, title, author, version, description, url, downloadUrl,
                    downloads, stars, newScore, gameVersions, loaders, dependencies,
                    sha512, sha256, fileName, premium, directDownloadable
            );
        }

        public SearchResultEntry withRelease(String newVersion, String newDownloadUrl, boolean directDownloadable) {
            return new SearchResultEntry(
                    sourceId, projectId, title, author, newVersion, description, url, newDownloadUrl,
                    downloads, stars, score, gameVersions, loaders, dependencies,
                    sha512, sha256, fileName, premium, directDownloadable
            );
        }

        public SearchResultEntry withVersion(String newVersion) {
            return new SearchResultEntry(
                    sourceId, projectId, title, author, newVersion, description, url, downloadUrl,
                    downloads, stars, score, gameVersions, loaders, dependencies,
                    sha512, sha256, fileName, premium, directDownloadable
            );
        }
    }
}
