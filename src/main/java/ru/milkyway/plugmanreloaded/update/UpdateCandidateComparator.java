package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.UpdateModels.MatchConfidence;
import ru.milkyway.plugmanreloaded.update.UpdateModels.ReleaseChannel;
import ru.milkyway.plugmanreloaded.update.UpdateModels.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class UpdateCandidateComparator implements Comparator<UpdateCandidate> {

    private UpdateCandidateComparator() {}

    private static final List<String> SOURCE_TRUST_ORDER = List.of(
            "modrinth", "hangar", "direct", "github", "jenkins", "spigot", "spigot-premium", "ruspigot", "ruspigot-premium"
    );

    @Override
    public int compare(UpdateCandidate c1, UpdateCandidate c2) {
        return CANDIDATE_COMPARATOR.compare(c1, c2);
    }

    private static int paidRank(UpdateCandidate candidate) {
        return candidate.version() != null && UpdateSource.isPaidSource(candidate.version().sourceId()) ? 1 : 0;
    }

    private static boolean racesOnConfirmedNewerVersion(@Nullable UpdateCandidate candidate) {
        if (candidate == null) return false;
        if (candidate.identity().isPremium()) return false;
        UpdateStatus status = candidate.status();
        if (status == UpdateStatus.AMBIGUOUS_MATCH || status == UpdateStatus.FOUND_NOT_DOWNLOADABLE) {
            return false;
        }
        return status.hasNewerVersion() && candidate.installable();
    }

    private static final Comparator<RemoteVersion> VERSION_ORDER = (v1, v2) -> {
        String n1 = v1 == null ? null : v1.versionNumber();
        String n2 = v2 == null ? null : v2.versionNumber();
        if (n1 == null || n2 == null) return 0;
        return VersionCompare.compare(n1, n2);
    };

    private static final Comparator<UpdateCandidate> CANDIDATE_COMPARATOR = Comparator
            .<UpdateCandidate>comparingInt(c -> racesOnConfirmedNewerVersion(c) ? 1 : 0)
            .thenComparingInt(c -> confidenceRank(c.confidence()))
            .thenComparing(UpdateCandidate::version, VERSION_ORDER)
            .thenComparingInt(UpdateCandidateComparator::paidRank)
            .thenComparingInt(c -> statusRank(c.status()))
            .thenComparingInt(c -> c.version() != null ? channelRank(c.version().channel()) : 0)
            .thenComparingInt(c -> c.version() != null ? trustRank(c.version().sourceId()) : 0);

    private static int statusRank(@Nullable UpdateStatus status) {
        if (status == null) return -1;
        return switch (status) {
            case UPDATE_AVAILABLE -> 100;
            case PRERELEASE_ONLY -> 80;
            case COMPAT_UNKNOWN -> 60;
            case UP_TO_DATE -> 50;
            case PENDING_RESTART -> 45;
            case AMBIGUOUS_MATCH -> 40;
            case FOUND_NOT_DOWNLOADABLE -> 35;
            case NO_SOURCE, RATE_LIMITED, NETWORK_ERROR -> 0;
        };
    }

    private static int channelRank(@Nullable ReleaseChannel channel) {
        if (channel == null) return 0;
        return switch (channel) {
            case RELEASE -> 30;
            case BETA -> 20;
            case ALPHA -> 10;
            case UNKNOWN -> 0;
        };
    }

    private static int trustRank(@Nullable String sourceId) {
        if (sourceId == null) return -1;
        int idx = SOURCE_TRUST_ORDER.indexOf(sourceId.toLowerCase(Locale.ROOT));
        return idx >= 0 ? (SOURCE_TRUST_ORDER.size() - idx) : 0;
    }

    private static int confidenceRank(@Nullable MatchConfidence confidence) {
        if (confidence == null) return 0;
        return switch (confidence) {
            case CONFIRMED -> 30;
            case LIKELY -> 20;
            case WEAK -> 10;
        };
    }

    public static final UpdateCandidateComparator INSTANCE = new UpdateCandidateComparator();
}
