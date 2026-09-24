package ru.milkyway.plugmanreloaded.update;

import ru.milkyway.plugmanreloaded.update.UpdateModels.*;
import ru.milkyway.plugmanreloaded.update.VersionResolver;

import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class VersionResolverTest {

    private ServerProfile createProfile() {
        return ServerProfile.of("1.20.4", Set.of("paper", "bukkit"), 17);
    }

    @Test
    void testResolveNewerVersionAvailable() {
        ServerProfile profile = createProfile();
        VersionResolver VersionResolver = new VersionResolver(profile, false);

        PluginIdentity identity = new PluginIdentity("Vault", "net.milkbowl.vault.Vault", "1.7.2", List.of("MilkBowl"), "https://example.com", null, null, null);
        UpdateSource.ProjectMatch match = new UpdateSource.ProjectMatch("hangar", "Vault", MatchConfidence.LIKELY, MatchReason.CATALOG, null);

        RemoteVersion v1 = new RemoteVersion("hangar", "Vault", "https://example.com/project", "1.7.3",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper", "bukkit"),
                "https://example.com/v1.7.3.jar", "Vault-1.7.3.jar", null, null, 1024L, Instant.now());

        UpdateCandidate candidate = VersionResolver.resolve(identity, match, List.of(v1));

        assertNotNull(candidate);
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, candidate.status());
        assertEquals("1.7.3", candidate.version().versionNumber());
    }

    @Test
    void testResolveAlreadyUpToDate() {
        ServerProfile profile = createProfile();
        VersionResolver VersionResolver = new VersionResolver(profile, false);

        PluginIdentity identity = new PluginIdentity("Vault", "net.milkbowl.vault.Vault", "1.7.3", List.of("MilkBowl"), "https://example.com", null, null, null);
        UpdateSource.ProjectMatch match = new UpdateSource.ProjectMatch("hangar", "Vault", MatchConfidence.LIKELY, MatchReason.CATALOG, null);

        RemoteVersion v1 = new RemoteVersion("hangar", "Vault", "https://example.com/project", "1.7.3",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper", "bukkit"),
                "https://example.com/v1.7.3.jar", "Vault-1.7.3.jar", null, null, 1024L, Instant.now());

        UpdateCandidate candidate = VersionResolver.resolve(identity, match, List.of(v1));

        assertNotNull(candidate);
        assertEquals(UpdateStatus.UP_TO_DATE, candidate.status());
    }

    @Test
    void testResolvePrereleaseFiltering() {
        ServerProfile profile = createProfile();
        VersionResolver resolverIgnorePre = new VersionResolver(profile, false);
        VersionResolver resolverAllowPre = new VersionResolver(profile, true);

        PluginIdentity identity = new PluginIdentity("DecentHolograms", "eu.decentsoftware.holograms.DecentHolograms", "2.8.10", List.of("author"), "https://example.com", null, null, null);
        UpdateSource.ProjectMatch match = new UpdateSource.ProjectMatch("modrinth", "decent-holograms", MatchConfidence.LIKELY, MatchReason.CATALOG, null);

        RemoteVersion release = new RemoteVersion("modrinth", "decent-holograms", "https://example.com", "2.8.11",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper"),
                "https://example.com/2.8.11.jar", "DH-2.8.11.jar", null, null, 1024L, Instant.now().minusSeconds(3600));

        RemoteVersion beta = new RemoteVersion("modrinth", "decent-holograms", "https://example.com", "2.8.12-BETA",
                ReleaseChannel.BETA, Set.of("1.20.4"), Set.of("paper"),
                "https://example.com/2.8.12-beta.jar", "DH-2.8.12-BETA.jar", null, null, 1024L, Instant.now());

        UpdateCandidate candidateIgnore = resolverIgnorePre.resolve(identity, match, List.of(release, beta));
        assertEquals("2.8.11", candidateIgnore.version().versionNumber());
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, candidateIgnore.status());

        UpdateCandidate candidateAllow = resolverAllowPre.resolve(identity, match, List.of(release, beta));
        assertEquals("2.8.12-BETA", candidateAllow.version().versionNumber());
        assertEquals(UpdateStatus.PRERELEASE_ONLY, candidateAllow.status());
    }

    @Test
    void testResolveSha256Confirmation() {
        ServerProfile profile = createProfile();
        VersionResolver VersionResolver = new VersionResolver(profile, false);

        String testSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        PluginIdentity identity = new PluginIdentity("Vault", "net.milkbowl.vault.Vault", "1.7.0", List.of("MilkBowl"), "https://example.com", null, testSha256, null);
        UpdateSource.ProjectMatch match = new UpdateSource.ProjectMatch("hangar", "Vault", MatchConfidence.WEAK, MatchReason.NAME_FUZZY, null);

        RemoteVersion v1 = new RemoteVersion("hangar", "Vault", "https://example.com", "1.7.0",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper"),
                "https://example.com/1.7.0.jar", "Vault-1.7.0.jar", null, testSha256, 1024L, Instant.now());

        UpdateCandidate candidate = VersionResolver.resolve(identity, match, List.of(v1));
        assertEquals(MatchConfidence.CONFIRMED, candidate.confidence());
    }

    @Test
    void testNewerPublishDateWinsOverHigherVersionNumber() {
        ServerProfile profile = createProfile();
        VersionResolver VersionResolver = new VersionResolver(profile, false);

        PluginIdentity identity = new PluginIdentity("MoneyFromMobs", "me.chocolf.moneyfrommobs.MoneyFromMobs",
                "4.9", List.of("chocolf"), null, null, null, null);
        UpdateSource.ProjectMatch match = new UpdateSource.ProjectMatch("github", "chocolf/MoneyFromMobs",
                MatchConfidence.CONFIRMED, MatchReason.HASH_MATCH, null);

        RemoteVersion newest = new RemoteVersion("github", "chocolf/MoneyFromMobs", "https://example.com", "4.9",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper", "bukkit"),
                "https://example.com/4.9.jar", "MFM-4.9.jar", null, null, 1024L, Instant.parse("2024-06-19T00:00:00Z"));

        RemoteVersion older = new RemoteVersion("github", "chocolf/MoneyFromMobs", "https://example.com", "4.82",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper", "bukkit"),
                "https://example.com/4.82.jar", "MFM-4.82.jar", null, null, 1024L, Instant.parse("2024-04-29T00:00:00Z"));

        UpdateCandidate candidate = VersionResolver.resolve(identity, match, List.of(older, newest));

        assertEquals(UpdateStatus.UP_TO_DATE, candidate.status(),
                "4.82 вышла раньше 4.9 и не должна предлагаться как обновление");
        assertEquals("4.9", candidate.version().versionNumber());
    }

    @Test
    void testVersionOrderUsedWhenPublishDatesUnknown() {
        ServerProfile profile = createProfile();
        VersionResolver VersionResolver = new VersionResolver(profile, false);

        PluginIdentity identity = new PluginIdentity("Sample", "t.Sample", "1.0",
                List.of("author"), null, null, null, null);
        UpdateSource.ProjectMatch match = new UpdateSource.ProjectMatch("spigot", "1",
                MatchConfidence.LIKELY, MatchReason.CATALOG, null);

        RemoteVersion low = new RemoteVersion("spigot", "1", "https://example.com", "1.1",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper"),
                "https://example.com/1.1.jar", "S-1.1.jar", null, null, 1024L, null);

        RemoteVersion high = new RemoteVersion("spigot", "1", "https://example.com", "1.3",
                ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper"),
                "https://example.com/1.3.jar", "S-1.3.jar", null, null, 1024L, null);

        UpdateCandidate candidate = VersionResolver.resolve(identity, match, List.of(low, high));

        assertEquals("1.3", candidate.version().versionNumber(),
                "Без дат публикации порядок обязан определяться номером версии");
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, candidate.status());
    }
}
