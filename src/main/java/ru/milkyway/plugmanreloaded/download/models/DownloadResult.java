package ru.milkyway.plugmanreloaded.download.models;

import java.util.Collections;
import java.util.List;

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

