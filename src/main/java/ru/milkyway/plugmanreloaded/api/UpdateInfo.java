package ru.milkyway.plugmanreloaded.api;

import ru.milkyway.plugmanreloaded.update.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateCandidate;

public record UpdateInfo(
        String pluginName,
        String currentVersion,
        String newVersion,
        String sourceId,
        String sourceUrl,
        boolean downloadable,
        boolean isPremium,
        boolean hasUpdate
) {
    public static UpdateInfo from(UpdateCandidate candidate) {
        PluginIdentity identity = candidate.identity();
        RemoteVersion version = candidate.version();

        return new UpdateInfo(
                identity.pluginName(),
                identity.currentVersion(),
                version != null ? version.versionNumber() : "Unknown",
                version != null ? version.sourceId() : "none",
                version != null ? version.downloadUrl() : "",
                version != null && version.downloadable(),
                identity.isPremium(),
                candidate.status().hasNewerVersion()
        );
    }
}
