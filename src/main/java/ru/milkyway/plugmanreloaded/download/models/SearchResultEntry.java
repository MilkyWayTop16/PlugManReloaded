package ru.milkyway.plugmanreloaded.download.models;

import java.util.Collections;
import java.util.List;

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

