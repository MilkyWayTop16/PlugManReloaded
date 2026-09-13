package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.api.UpdateInfo;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UpdateModels {

    private UpdateModels() {}

    public enum PluginEdition {
        FREE,
        PREMIUM,
        UNKNOWN;

        public boolean isPremium() {
            return this == PREMIUM;
        }
    }

    public enum ReleaseChannel {
        RELEASE,
        BETA,
        ALPHA,
        UNKNOWN;

        public boolean isPrerelease() {
            return this == BETA || this == ALPHA;
        }

        public static ReleaseChannel parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) return RELEASE;
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (value.contains("alpha") || value.contains("snapshot") || value.contains("dev")) return ALPHA;
            if (value.contains("beta") || value.contains("rc") || value.contains("pre")) return BETA;
            return RELEASE;
        }
    }

    public enum MatchConfidence {
        CONFIRMED,
        LIKELY,
        WEAK;

        public boolean allowsAutoInstall() {
            return this != WEAK;
        }
    }

    public enum MatchReason {
        HASH_MATCH("hash"),
        MAIN_MATCH("main-class"),
        PLUGIN_YML_MATCH("plugin-yml"),
        CATALOG("catalog"),
        JAR_REFERENCE("jar-reference"),
        NAME_FUZZY("name"),
        NONE("not-found");

        private static final String PREFIX = "actions.update.match-reasons.";

        private final String key;

        MatchReason(String key) {
            this.key = key;
        }

        public String messageKey() {
            return PREFIX + key;
        }
    }

    public enum UpdateStatus {
        UP_TO_DATE("update.up-to-date"),
        UPDATE_AVAILABLE("update.confirm"),
        FOUND_NOT_DOWNLOADABLE("update.not-downloadable"),
        COMPAT_UNKNOWN("update.confirm-compat-unknown"),
        PRERELEASE_ONLY("update.confirm-prerelease"),
        AMBIGUOUS_MATCH("update.confirm-ambiguous"),
        PENDING_RESTART("update.pending-restart"),
        NO_SOURCE("update.no-source"),
        RATE_LIMITED("update.rate-limited"),
        NETWORK_ERROR("update.network-error");

        private final String actionKey;

        UpdateStatus(String actionKey) {
            this.actionKey = actionKey;
        }

        public String actionKey() {
            return actionKey;
        }

        public boolean hasNewerVersion() {
            return this == UPDATE_AVAILABLE
                    || this == FOUND_NOT_DOWNLOADABLE
                    || this == COMPAT_UNKNOWN
                    || this == PRERELEASE_ONLY
                    || this == AMBIGUOUS_MATCH;
        }
    }

    public record PluginIdentity(
            String pluginName,
            String mainClass,
            String currentVersion,
            List<String> authors,
            String website,
            String sha1,
            String sha256,
            File jarFile,
            String pendingVersion,
            PluginEdition edition
    ) {
        public PluginIdentity(String pluginName, String mainClass, String currentVersion, List<String> authors, String website, String sha1, String sha256, File jarFile) {
            this(pluginName, mainClass, currentVersion, authors, website, sha1, sha256, jarFile, null, PluginEdition.FREE);
        }

        public PluginIdentity(String pluginName, String mainClass, String currentVersion, List<String> authors, String website, String sha1, String sha256, File jarFile, String pendingVersion) {
            this(pluginName, mainClass, currentVersion, authors, website, sha1, sha256, jarFile, pendingVersion, PluginEdition.FREE);
        }

        public boolean isPremium() {
            return edition != null && edition.isPremium();
        }

        public boolean hashesAvailable() {
            return sha1 != null && !sha1.isBlank();
        }
    }

    public record RemoteVersion(
            String sourceId,
            String projectRef,
            String projectUrl,
            String versionNumber,
            ReleaseChannel channel,
            Set<String> gameVersions,
            Set<String> loaders,
            String downloadUrl,
            String fileName,
            String expectedSha1,
            String expectedSha256,
            long sizeBytes,
            Instant published
    ) {
        public boolean downloadable() {
            return downloadUrl != null && !downloadUrl.isBlank();
        }

        public boolean compatibilityKnown() {
            return gameVersions != null && !gameVersions.isEmpty();
        }

        public boolean supportsGameVersion(String serverVersion) {
            if (!compatibilityKnown() || serverVersion == null || serverVersion.isBlank()) {
                return false;
            }
            String server = serverVersion.trim();
            for (String gv : gameVersions) {
                if (gv == null || gv.isBlank()) continue;
                String clean = gv.trim();
                if (clean.equalsIgnoreCase("all") || clean.equals("*")) {
                    return true;
                }
                if (clean.equalsIgnoreCase(server)) {
                    return true;
                }
                if (clean.endsWith(".x") || clean.endsWith(".X") || clean.endsWith(".*")) {
                    String prefix = clean.substring(0, clean.length() - 2);
                    if (server.equals(prefix) || server.startsWith(prefix + ".")) {
                        return true;
                    }
                }
                if (clean.matches("^\\d+\\.\\d+$")) {
                    if (server.equals(clean) || server.startsWith(clean + ".")) {
                        return true;
                    }
                }
                int dash = clean.indexOf('-');
                if (dash > 0) {
                    String min = clean.substring(0, dash).trim();
                    String max = clean.substring(dash + 1).trim();
                    if (VersionCompare.compare(server, min) >= 0 && VersionCompare.compare(server, max) <= 0) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

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

        public UpdateInfo toUpdateInfo() {
            return new UpdateInfo(
                    identity.pluginName(),
                    identity.currentVersion(),
                    version != null ? version.versionNumber() : "Unknown",
                    version != null ? version.sourceId() : "none",
                    version != null ? version.downloadUrl() : "",
                    version != null && version.downloadable(),
                    identity.isPremium(),
                    status.hasNewerVersion()
            );
        }
    }

    public enum InstallStatus {
        INSTALLED("update.installed"),
        DOWNLOAD_FAILED("update.download-failed"),
        HASH_MISMATCH("update.hash-mismatch"),
        WRONG_PLUGIN("update.wrong-plugin"),
        MISSING_DEPENDENCY("update.missing-dependency"),
        PENDING_RESTART("update.pending-restart"),
        ROLLED_BACK("update.rolled-back"),
        NOT_INSTALLABLE("update.not-installable");

        private final String actionKey;

        InstallStatus(String actionKey) {
            this.actionKey = actionKey;
        }

        public String actionKey() {
            return actionKey;
        }

        public boolean success() {
            return this == INSTALLED || this == PENDING_RESTART;
        }
    }

    public record InstallResult(
            InstallStatus outcome,
            String pluginName,
            String fromVersion,
            String toVersion,
            String detail,
            List<String> dependencyWarnings
    ) {
        public InstallResult(InstallStatus outcome, String pluginName, String fromVersion, String toVersion, String detail) {
            this(outcome, pluginName, fromVersion, toVersion, detail, List.of());
        }

        public static InstallResult of(InstallStatus outcome, String pluginName, String fromVersion, String toVersion) {
            return new InstallResult(outcome, pluginName, fromVersion, toVersion, "", List.of());
        }

        public static InstallResult of(InstallStatus outcome, String pluginName, String fromVersion, String toVersion, List<String> warnings) {
            return new InstallResult(outcome, pluginName, fromVersion, toVersion, "", warnings != null ? warnings : List.of());
        }

        public static InstallResult failed(InstallStatus outcome, String pluginName, String detail) {
            return new InstallResult(outcome, pluginName, "", "", detail == null ? "" : detail, List.of());
        }

        public static InstallResult failed(InstallStatus outcome, String pluginName, String detail, List<String> warnings) {
            return new InstallResult(outcome, pluginName, "", "", detail == null ? "" : detail, warnings != null ? warnings : List.of());
        }
    }
}
