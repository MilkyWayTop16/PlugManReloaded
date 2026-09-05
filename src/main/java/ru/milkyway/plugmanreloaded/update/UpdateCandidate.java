package ru.milkyway.plugmanreloaded.update;

public record UpdateCandidate(
        PluginIdentity identity,
        RemoteVersion version,
        MatchConfidence confidence,
        MatchReason reason,
        UpdateStatus status,
        String pageUrl
) {

    public static UpdateCandidate noSource(PluginIdentity identity) {
        return new UpdateCandidate(identity, null, MatchConfidence.WEAK, MatchReason.NONE, UpdateStatus.NO_SOURCE, null);
    }

    public static UpdateCandidate upToDate(PluginIdentity identity, RemoteVersion version,
                                           MatchConfidence confidence, MatchReason reason) {
        return new UpdateCandidate(identity, version, confidence, reason, UpdateStatus.UP_TO_DATE,
                version != null ? version.projectUrl() : null);
    }

    public static UpdateCandidate failed(PluginIdentity identity, UpdateStatus status) {
        return new UpdateCandidate(identity, null, MatchConfidence.WEAK, MatchReason.NONE, status, null);
    }

    public boolean installable() {
        return version != null
                && version.downloadable()
                && confidence != MatchConfidence.WEAK
                && (status == UpdateStatus.UPDATE_AVAILABLE
                    || status == UpdateStatus.PRERELEASE_ONLY
                    || status == UpdateStatus.COMPAT_UNKNOWN);
    }

    public boolean autoInstallable() {
        return installable()
                && confidence.allowsAutoInstall()
                && (status == UpdateStatus.UPDATE_AVAILABLE || status == UpdateStatus.COMPAT_UNKNOWN)
                && version.channel() == ReleaseChannel.RELEASE;
    }

    public String remoteVersionNumber() {
        return version != null ? version.versionNumber() : "";
    }
}

