package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.UpdateModels.*;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionUtil {

    private VersionUtil() {}

    private static final List<String> PRE_QUALIFIERS = List.of("snapshot", "dev", "alpha", "beta", "rc", "pre");
    private static final Set<String> RELEASE_QUALIFIERS = Set.of("release", "final", "ga");

    private static final Set<String> PLATFORM_TOKENS = Set.of(
            "bukkit", "spigot", "paper", "purpur", "folia", "velocity",
            "bungee", "bungeecord", "waterfall", "sponge", "fabric", "forge", "neoforge"
    );

    public static int compare(String left, String right) {
        List<String> a = split(left);
        List<String> b = split(right);
        int max = Math.max(a.size(), b.size());

        for (int i = 0; i < max; i++) {
            String partA = i < a.size() ? a.get(i) : "";
            String partB = i < b.size() ? b.get(i) : "";

            Integer numA = asNumber(partA);
            Integer numB = asNumber(partB);

            if (partA.isEmpty() && numB != null) {
                numA = 0;
            }
            if (partB.isEmpty() && numA != null) {
                numB = 0;
            }

            if (numA != null && numB != null) {
                int cmp = Integer.compare(numA, numB);
                if (cmp != 0) return cmp;
                continue;
            }

            if (numA != null) return 1;
            if (numB != null) return -1;

            int cmp = compareQualifiers(partA, partB);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    public static boolean parseable(String version) {
        for (String part : split(version)) {
            if (asNumber(part) != null) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(@Nullable String version) {
        List<String> parts = new ArrayList<>();
        if (version == null || version.isBlank()) {
            return parts;
        }

        String normalized = version.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }

        StringBuilder current = new StringBuilder();
        boolean lastWasDigit = false;

        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == '+' || c == ' ') {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                lastWasDigit = false;
                continue;
            }

            boolean isDigit = Character.isDigit(c);
            if (current.length() > 0 && isDigit != lastWasDigit) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
            lastWasDigit = isDigit;
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        while (!parts.isEmpty() && PLATFORM_TOKENS.contains(parts.get(parts.size() - 1))) {
            parts.remove(parts.size() - 1);
        }
        return parts;
    }

    private static @Nullable Integer asNumber(@Nullable String part) {
        if (part == null || part.isEmpty()) return null;
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compareQualifiers(String left, String right) {
        if (left.equals(right)) return 0;

        boolean leftIsRelease = left.isEmpty() || RELEASE_QUALIFIERS.contains(left);
        boolean rightIsRelease = right.isEmpty() || RELEASE_QUALIFIERS.contains(right);

        if (leftIsRelease && rightIsRelease) {
            return 0;
        }

        int indexLeft = PRE_QUALIFIERS.indexOf(left);
        int indexRight = PRE_QUALIFIERS.indexOf(right);

        if (leftIsRelease && indexRight >= 0) {
            return 1;
        }
        if (rightIsRelease && indexLeft >= 0) {
            return -1;
        }

        if (indexLeft >= 0 && indexRight >= 0) {
            return Integer.compare(indexLeft, indexRight);
        }
        if (indexLeft >= 0) return -1;
        if (indexRight >= 0) return 1;
        return left.compareTo(right);
    }

    public static final class Resolver {

        private static final Pattern JAR_VERSION_PATTERN = Pattern.compile(
                "[-_.](?:v|ver|version)?(\\d+(?:[._-]\\d+)*(?:[._-](?:alpha|beta|rc|snapshot|dev|release|patch|final|b\\d+|build\\d+|[a-zA-Z0-9]+))*)$",
                Pattern.CASE_INSENSITIVE
        );

        private static final Comparator<RemoteVersion> BY_DATE_THEN_VERSION = (a, b) -> {
            int cmp = b.published().compareTo(a.published());
            if (cmp != 0) return cmp;
            return VersionUtil.compare(b.versionNumber(), a.versionNumber());
        };

        private static final Comparator<RemoteVersion> BY_VERSION_THEN_DATE = (a, b) -> {
            int cmp = VersionUtil.compare(b.versionNumber(), a.versionNumber());
            if (cmp != 0) return cmp;
            if (b.published() != null && a.published() != null) {
                return b.published().compareTo(a.published());
            }
            if (b.published() != null) return 1;
            if (a.published() != null) return -1;
            return 0;
        };

        private final ServerProfile profile;
        private final boolean allowPrerelease;

        public Resolver(ServerProfile profile, boolean allowPrerelease) {
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

        private @Nullable String extractVersionFromFilename(@Nullable String filename) {
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

        private boolean allDatesKnown(List<RemoteVersion> versions) {
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
            if (VersionUtil.parseable(pending) && VersionUtil.parseable(remote)) {
                return !VersionUtil.isNewer(remote, pending);
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
            if (!VersionUtil.parseable(declared) || !VersionUtil.parseable(fromFileName)) {
                return declared;
            }
            if (!VersionUtil.isNewer(fromFileName, declared)) {
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
                if (VersionUtil.parseable(number) && VersionUtil.compare(number, version) == 0) {
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

            if (VersionUtil.parseable(effectiveInstalled) && VersionUtil.parseable(remote)) {
                return VersionUtil.isNewer(remote, effectiveInstalled);
            }

            if (VersionUtil.parseable(effectiveInstalled) && !VersionUtil.parseable(remote)) {
                return false;
            }

            return !effectiveInstalled.equalsIgnoreCase(remote);
        }
    }
}
