package ru.milkyway.plugmanreloaded.update;

import ru.milkyway.plugmanreloaded.update.UpdateModels.*;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateComparatorTest {

    private static final PluginIdentity DUMMY_ID = new PluginIdentity(
            "TestPlugin", "com.test.Main", "1.0", List.of("Author"), null, null, null, new File("TestPlugin.jar")
    );

    private static RemoteVersion mockVersion(String source, String ver, ReleaseChannel channel, boolean dl) {
        return new RemoteVersion(
                source, "ref", "http://url", ver, channel,
                Set.of(), Set.of(), dl ? "http://dl" : null, "test.jar", null, null, 100L, null
        );
    }

    @Test
    void testTransitivityAndAntisymmetry() {
        UpdateStatus[] statuses = {
                UpdateStatus.UPDATE_AVAILABLE,
                UpdateStatus.PRERELEASE_ONLY,
                UpdateStatus.COMPAT_UNKNOWN,
                UpdateStatus.AMBIGUOUS_MATCH,
                UpdateStatus.FOUND_NOT_DOWNLOADABLE,
                UpdateStatus.PENDING_RESTART,
                UpdateStatus.UP_TO_DATE
        };
        String[] versions = {"1.0", "1.5", "2.0"};
        String[] sources = {"modrinth", "github", "spigot"};

        List<UpdateCandidate> pool = new ArrayList<>();
        for (UpdateStatus st : statuses) {
            for (String v : versions) {
                for (String src : sources) {
                    MatchConfidence conf = st == UpdateStatus.AMBIGUOUS_MATCH ? MatchConfidence.WEAK : MatchConfidence.CONFIRMED;
                    pool.add(new UpdateCandidate(
                            DUMMY_ID,
                            mockVersion(src, v, ReleaseChannel.RELEASE, true),
                            conf,
                            MatchReason.NAME_FUZZY,
                            st,
                            "http://page"
                    ));
                }
            }
        }

        int size = pool.size();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                UpdateCandidate a = pool.get(i);
                UpdateCandidate b = pool.get(j);
                int cmpAB = UpdateCandidateComparator.INSTANCE.compare(a, b);
                int cmpBA = UpdateCandidateComparator.INSTANCE.compare(b, a);

                assertEquals(Integer.signum(cmpAB), -Integer.signum(cmpBA));

                if (cmpAB >= 0) {
                    for (int k = 0; k < size; k++) {
                        UpdateCandidate c = pool.get(k);
                        int cmpBC = UpdateCandidateComparator.INSTANCE.compare(b, c);
                        if (cmpBC >= 0) {
                            int cmpAC = UpdateCandidateComparator.INSTANCE.compare(a, c);
                            assertTrue(cmpAC >= 0);
                        }
                    }
                }
            }
        }
    }

    @Test
    void testDeterminismUnderAllPermutations() {
        UpdateCandidate c1 = new UpdateCandidate(
                DUMMY_ID, mockVersion("modrinth", "1.0", ReleaseChannel.RELEASE, true),
                MatchConfidence.CONFIRMED, MatchReason.HASH_MATCH, UpdateStatus.UPDATE_AVAILABLE, "http://p1"
        );
        UpdateCandidate c2 = new UpdateCandidate(
                DUMMY_ID, mockVersion("spigot", "2.0", ReleaseChannel.RELEASE, false),
                MatchConfidence.LIKELY, MatchReason.NAME_FUZZY, UpdateStatus.FOUND_NOT_DOWNLOADABLE, "http://p2"
        );
        UpdateCandidate c3 = new UpdateCandidate(
                DUMMY_ID, mockVersion("hangar", "1.5", ReleaseChannel.RELEASE, true),
                MatchConfidence.CONFIRMED, MatchReason.CATALOG, UpdateStatus.COMPAT_UNKNOWN, "http://p3"
        );
        UpdateCandidate c4 = new UpdateCandidate(
                DUMMY_ID, mockVersion("github", "1.0", ReleaseChannel.RELEASE, true),
                MatchConfidence.WEAK, MatchReason.NAME_FUZZY, UpdateStatus.AMBIGUOUS_MATCH, "http://p4"
        );

        List<UpdateCandidate> base = new ArrayList<>(List.of(c1, c2, c3, c4));
        List<List<UpdateCandidate>> permutations = new ArrayList<>();
        generatePermutations(base, 0, permutations);

        assertEquals(24, permutations.size());

        UpdateCandidate expectedWinner = null;
        for (List<UpdateCandidate> perm : permutations) {
            List<UpdateCandidate> copy = new ArrayList<>(perm);
            copy.sort(UpdateCandidateComparator.INSTANCE.reversed());
            UpdateCandidate winner = copy.get(0);
            if (expectedWinner == null) {
                expectedWinner = winner;
            } else {
                assertEquals(expectedWinner, winner);
            }
        }
        assertEquals("hangar", expectedWinner.version().sourceId());
        assertEquals("1.5", expectedWinner.version().versionNumber());
    }

    @Test
    void testConfirmedBeatsHigherVersionWithLowerConfidence() {
        UpdateCandidate spigot20 = new UpdateCandidate(
                DUMMY_ID, mockVersion("spigot", "2.0", ReleaseChannel.RELEASE, false),
                MatchConfidence.LIKELY, MatchReason.NAME_FUZZY, UpdateStatus.FOUND_NOT_DOWNLOADABLE, "http://spigot"
        );
        UpdateCandidate modrinth19 = new UpdateCandidate(
                DUMMY_ID, mockVersion("modrinth", "1.9", ReleaseChannel.RELEASE, true),
                MatchConfidence.CONFIRMED, MatchReason.CATALOG, UpdateStatus.UPDATE_AVAILABLE, "http://modrinth"
        );

        List<UpdateCandidate> list = new ArrayList<>(List.of(spigot20, modrinth19));
        list.sort(UpdateCandidateComparator.INSTANCE.reversed());
        assertEquals("modrinth", list.get(0).version().sourceId());
        assertEquals("1.9", list.get(0).version().versionNumber());
    }

    @Test
    void testFreshnessWithinSameConfidence() {
        UpdateCandidate hangar20 = new UpdateCandidate(
                DUMMY_ID, mockVersion("hangar", "2.0", ReleaseChannel.RELEASE, true),
                MatchConfidence.CONFIRMED, MatchReason.CATALOG, UpdateStatus.UPDATE_AVAILABLE, "http://hangar"
        );
        UpdateCandidate modrinth19 = new UpdateCandidate(
                DUMMY_ID, mockVersion("modrinth", "1.9", ReleaseChannel.RELEASE, true),
                MatchConfidence.CONFIRMED, MatchReason.CATALOG, UpdateStatus.UPDATE_AVAILABLE, "http://modrinth"
        );

        List<UpdateCandidate> list = new ArrayList<>(List.of(modrinth19, hangar20));
        list.sort(UpdateCandidateComparator.INSTANCE.reversed());
        assertEquals("hangar", list.get(0).version().sourceId());
        assertEquals("2.0", list.get(0).version().versionNumber());
    }

    @Test
    void testConfirmedBeatsAmbiguousMatchEvenWithHigherVersion() {
        UpdateCandidate confirmed20 = new UpdateCandidate(
                DUMMY_ID, mockVersion("modrinth", "2.0", ReleaseChannel.RELEASE, true),
                MatchConfidence.CONFIRMED, MatchReason.CATALOG, UpdateStatus.UPDATE_AVAILABLE, "http://modrinth"
        );
        UpdateCandidate ambiguous99 = new UpdateCandidate(
                DUMMY_ID, mockVersion("github", "9.9", ReleaseChannel.RELEASE, true),
                MatchConfidence.WEAK, MatchReason.NAME_FUZZY, UpdateStatus.AMBIGUOUS_MATCH, "http://github"
        );

        List<UpdateCandidate> list = new ArrayList<>(List.of(confirmed20, ambiguous99));
        list.sort(UpdateCandidateComparator.INSTANCE.reversed());
        assertEquals(confirmed20, list.get(0));
    }

    @Test
    void testDirectDownloadBeatsWebOnlyOnSameVersion() {
        UpdateCandidate direct = new UpdateCandidate(
                DUMMY_ID, mockVersion("github", "2.0", ReleaseChannel.RELEASE, true),
                MatchConfidence.LIKELY, MatchReason.NAME_FUZZY, UpdateStatus.UPDATE_AVAILABLE, "http://github"
        );
        UpdateCandidate webOnly = new UpdateCandidate(
                DUMMY_ID, mockVersion("spigot", "2.0", ReleaseChannel.RELEASE, false),
                MatchConfidence.LIKELY, MatchReason.NAME_FUZZY, UpdateStatus.FOUND_NOT_DOWNLOADABLE, "http://spigot"
        );

        List<UpdateCandidate> list = new ArrayList<>(List.of(direct, webOnly));
        list.sort(UpdateCandidateComparator.INSTANCE.reversed());
        assertEquals(direct, list.get(0));
    }

    @Test
    void testPendingRestartCandidate() {
        PluginIdentity pendingIdentity = new PluginIdentity(
                "ViaVersion", "com.viaversion.ViaVersion", "5.7.2",
                List.of("ViaVersion"), null, null, null, new File("ViaVersion-5.7.2.jar"), "5.11.0"
        );
        ServerProfile profile = ServerProfile.of("1.20.4", Set.of("paper"));
        VersionResolver resolver = new VersionResolver(profile, false);
        ru.milkyway.plugmanreloaded.update.source.UpdateSource.ProjectMatch match =
                new ru.milkyway.plugmanreloaded.update.source.UpdateSource.ProjectMatch(
                        "modrinth", "viaversion", "ViaVersion", MatchConfidence.CONFIRMED, MatchReason.NAME_FUZZY, null
                );
        RemoteVersion remoteVer = mockVersion("modrinth", "5.11.0", ReleaseChannel.RELEASE, true);
        UpdateCandidate candidate = resolver.resolve(pendingIdentity, match, List.of(remoteVer));

        assertEquals(UpdateStatus.PENDING_RESTART, candidate.status());
        assertTrue(!candidate.installable());
    }

    private static void generatePermutations(List<UpdateCandidate> list, int k, List<List<UpdateCandidate>> out) {
        for (int i = k; i < list.size(); i++) {
            Collections.swap(list, i, k);
            generatePermutations(list, k + 1, out);
            Collections.swap(list, k, i);
        }
        if (k == list.size() - 1) {
            out.add(new ArrayList<>(list));
        }
    }
}
