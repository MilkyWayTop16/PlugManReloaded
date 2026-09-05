package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionResolver {

    private final ServerProfile profile;
    private final boolean allowPrerelease;

    public VersionResolver(ServerProfile profile, boolean allowPrerelease) {
        this.profile = profile;
        this.allowPrerelease = allowPrerelease;
    }

    public UpdateCandidate resolve(PluginIdentity identity,
                                   UpdateSource.ProjectMatch match,
                                   @Nullable List<RemoteVersion> versions) {

        if (versions == null || versions.isEmpty()) {
            return UpdateCandidate.noSource(identity);
        }

        MatchConfidence confidence = confirmByInstalledHash(identity, match, versions);
        String effectiveInstalled = resolveInstalledVersion(identity, versions);

        List<RemoteVersion> platformFit = new ArrayList<>();
        for (RemoteVersion version : versions) {
            if (profile.supportsLoader(version.loaders())) {
                platformFit.add(version);
            }
        }
        if (platformFit.isEmpty()) {
            return UpdateCandidate.noSource(identity);
        }

        List<RemoteVersion> compatible = new ArrayList<>();
        List<RemoteVersion> unknownCompat = new ArrayList<>();
        for (RemoteVersion version : platformFit) {
            if (!version.compatibilityKnown()) {
                unknownCompat.add(version);
            } else if (!profile.versionKnown() || version.supportsGameVersion(profile.minecraftVersion())) {
                compatible.add(version);
            }
        }

        RemoteVersion best = pickNewest(filterChannel(compatible, allowPrerelease));
        if (best != null) {
            UpdateStatus status = best.channel().isPrerelease()
                    ? UpdateStatus.PRERELEASE_ONLY
                    : UpdateStatus.UPDATE_AVAILABLE;
            return classify(identity, match, confidence, best, status, effectiveInstalled);
        }

        RemoteVersion prerelease = pickNewest(onlyPrerelease(compatible));
        if (prerelease != null) {
            UpdateStatus status = allowPrerelease ? UpdateStatus.PRERELEASE_ONLY : UpdateStatus.UP_TO_DATE;
            if (status == UpdateStatus.UP_TO_DATE) {
                return UpdateCandidate.upToDate(identity, prerelease, confidence, match.reason());
            }
            return classify(identity, match, confidence, prerelease, UpdateStatus.PRERELEASE_ONLY, effectiveInstalled);
        }

        RemoteVersion unknown = pickNewest(filterChannel(unknownCompat, allowPrerelease));
        if (unknown != null) {
            return classify(identity, match, confidence, unknown, UpdateStatus.COMPAT_UNKNOWN, effectiveInstalled);
        }

        RemoteVersion newestKnown = pickNewest(compatible);
        return UpdateCandidate.upToDate(identity, newestKnown, confidence, match.reason());
    }

    private List<RemoteVersion> filterChannel(List<RemoteVersion> versions, boolean includePrerelease) {
        List<RemoteVersion> result = new ArrayList<>();
        for (RemoteVersion version : versions) {
            if (includePrerelease || !version.channel().isPrerelease()) {
                result.add(version);
            }
        }
        return result;
    }

    private List<RemoteVersion> onlyPrerelease(List<RemoteVersion> versions) {
        List<RemoteVersion> result = new ArrayList<>();
        for (RemoteVersion version : versions) {
            if (version.channel().isPrerelease()) {
                result.add(version);
            }
        }
        return result;
    }

    private static final Pattern JAR_VERSION_PATTERN = Pattern.compile(
            "[-_.](?:v|ver|version)?(\\d+(?:[._-]\\d+)*(?:[._-](?:alpha|beta|rc|snapshot|dev|release|patch|final|b\\d+|build\\d+|[a-zA-Z0-9]+))*)$",
            Pattern.CASE_INSENSITIVE
    );

    private static @Nullable String extractVersionFromFilename(@Nullable String filename) {
        if (filename == null || filename.isBlank()) return null;
        String clean = filename.trim();
        if (clean.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        Matcher matcher = JAR_VERSION_PATTERN.matcher(clean);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static final Comparator<RemoteVersion> BY_DATE_THEN_VERSION = (a, b) -> {
        int cmp = b.published().compareTo(a.published());
        if (cmp != 0) return cmp;
        return VersionCompare.compare(b.versionNumber(), a.versionNumber());
    };

    private static final Comparator<RemoteVersion> BY_VERSION_THEN_DATE = (a, b) -> {
        int cmp = VersionCompare.compare(b.versionNumber(), a.versionNumber());
        if (cmp != 0) return cmp;
        if (b.published() != null && a.published() != null) {
            return b.published().compareTo(a.published());
        }
        if (b.published() != null) return 1;
        if (a.published() != null) return -1;
        return 0;
    };

    private static boolean allDatesKnown(List<RemoteVersion> versions) {
        for (RemoteVersion version : versions) {
            if (version.published() == null) {
                return false;
            }
        }
        return true;
    }

    private @Nullable RemoteVersion pickNewest(List<RemoteVersion> versions) {
        if (versions.isEmpty()) return null;
        List<RemoteVersion> sorted = new ArrayList<>(versions);
        sorted.sort(allDatesKnown(versions) ? BY_DATE_THEN_VERSION : BY_VERSION_THEN_DATE);
        return sorted.get(0);
    }

    private MatchConfidence confirmByInstalledHash(PluginIdentity identity,
                                                   UpdateSource.ProjectMatch match,
                                                   List<RemoteVersion> versions) {
        MatchConfidence current = match.confidence();
        if (current == MatchConfidence.CONFIRMED) {
            return current;
        }

        String sha1 = identity.sha1();
        String sha256 = identity.sha256();
        if ((sha1 == null || sha1.isBlank()) && (sha256 == null || sha256.isBlank())) {
            return current;
        }

        for (RemoteVersion version : versions) {
            if (sha1 != null && version.expectedSha1() != null && sha1.equalsIgnoreCase(version.expectedSha1())) {
                return MatchConfidence.CONFIRMED;
            }
            if (sha256 != null && version.expectedSha256() != null && sha256.equalsIgnoreCase(version.expectedSha256())) {
                return MatchConfidence.CONFIRMED;
            }
        }
        return current;
    }

    private UpdateCandidate classify(PluginIdentity identity,
                                     UpdateSource.ProjectMatch match,
                                     MatchConfidence confidence,
                                     RemoteVersion candidate,
                                     UpdateStatus statusIfNewer,
                                     String effectiveInstalled) {

        if (!isNewerThanInstalled(identity, candidate, effectiveInstalled)) {
            return UpdateCandidate.upToDate(identity, candidate, confidence, match.reason());
        }

        if (isPendingRestart(identity, candidate)) {
            return new UpdateCandidate(
                    identity,
                    candidate,
                    confidence,
                    match.reason(),
                    UpdateStatus.PENDING_RESTART,
                    candidate.projectUrl()
            );
        }

        UpdateStatus status = statusIfNewer;
        if (!candidate.downloadable()) {
            status = UpdateStatus.FOUND_NOT_DOWNLOADABLE;
        } else if (confidence == MatchConfidence.WEAK) {
            status = UpdateStatus.AMBIGUOUS_MATCH;
        }

        return new UpdateCandidate(
                identity,
                candidate,
                confidence,
                match.reason(),
                status,
                candidate.projectUrl()
        );
    }

    private boolean isPendingRestart(PluginIdentity identity, RemoteVersion candidate) {
        String pending = identity.pendingVersion();
        if (pending == null || pending.isBlank()) {
            return false;
        }
        String remote = candidate.versionNumber();
        if (remote == null || remote.isBlank()) {
            return false;
        }
        if (pending.equalsIgnoreCase(remote)) {
            return true;
        }
        if (VersionCompare.parseable(pending) && VersionCompare.parseable(remote)) {
            return !VersionCompare.isNewer(remote, pending);
        }
        return false;
    }

    private String resolveInstalledVersion(PluginIdentity identity, List<RemoteVersion> versions) {
        String declared = identity.currentVersion();
        if (identity.jarFile() == null) {
            return declared;
        }

        String fromFileName = extractVersionFromFilename(identity.jarFile().getName());
        if (fromFileName == null || fromFileName.isBlank()) {
            return declared;
        }
        if (declared == null || declared.isBlank()) {
            return fromFileName;
        }
        if (!VersionCompare.parseable(declared) || !VersionCompare.parseable(fromFileName)) {
            return declared;
        }
        if (!VersionCompare.isNewer(fromFileName, declared)) {
            return declared;
        }
        if (!isKnownRemoteVersion(fromFileName, versions)) {
            return declared;
        }
        return fromFileName;
    }

    private boolean isKnownRemoteVersion(String version, @Nullable List<RemoteVersion> versions) {
        if (versions == null) {
            return false;
        }
        for (RemoteVersion remote : versions) {
            String number = remote.versionNumber();
            if (number == null || number.isBlank()) {
                continue;
            }
            if (number.equalsIgnoreCase(version)) {
                return true;
            }
            if (VersionCompare.parseable(number) && VersionCompare.compare(number, version) == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isNewerThanInstalled(PluginIdentity identity, RemoteVersion candidate, @Nullable String effectiveInstalled) {
        String remote = candidate.versionNumber();
        if (remote == null || remote.isBlank()) {
            return false;
        }

        if (candidate.expectedSha1() != null && candidate.expectedSha1().equalsIgnoreCase(identity.sha1())) {
            return false;
        }
        if (candidate.expectedSha256() != null && candidate.expectedSha256().equalsIgnoreCase(identity.sha256())) {
            return false;
        }

        if (effectiveInstalled == null || effectiveInstalled.isBlank()) {
            return true;
        }

        if (effectiveInstalled.equalsIgnoreCase(remote)) {
            return false;
        }

        if (VersionCompare.parseable(effectiveInstalled) && VersionCompare.parseable(remote)) {
            return VersionCompare.isNewer(remote, effectiveInstalled);
        }

        if (VersionCompare.parseable(effectiveInstalled) && !VersionCompare.parseable(remote)) {
            return false;
        }

        return !effectiveInstalled.equalsIgnoreCase(remote);
    }
}

