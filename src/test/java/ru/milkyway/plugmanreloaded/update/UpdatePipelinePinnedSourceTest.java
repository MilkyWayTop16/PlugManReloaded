package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.update.UpdateModels.MatchConfidence;
import ru.milkyway.plugmanreloaded.update.UpdateModels.MatchReason;
import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.UpdateModels.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePipelinePinnedSourceTest {

    @Test
    void unavailablePinnedSourceDoesNotFallBackToAnotherProject(@TempDir Path tempDir) {
        File catalogFile = tempDir.resolve("sources-custom.yml").toFile();
        assertTrue(SourceCatalog.writeUserEntry(catalogFile, "CoinsEngine", "com.example.CoinsEngine",
                new SourceCatalog.CatalogSource("modrinth", "different-project", null, Map.of())));
        assertTrue(SourceCatalog.writeUserEntry(catalogFile, "CoinsEngine", "com.example.CoinsEngine",
                SourceCatalog.pinnedInstallation("github", "owner/coins-engine", null,
                        "1.0", "abcd", 100L, "CoinsEngine.jar")));

        FakeSource pinned = new FakeSource("github", List.of());
        FakeSource fallback = new FakeSource("modrinth", List.of(remote("modrinth", "different-project")));
        SourceCatalog catalog = new SourceCatalog(catalogFile);
        UpdatePipeline pipeline = new UpdatePipeline(null, List.of(pinned, fallback), null, null, catalog);
        PluginIdentity identity = new PluginIdentity("CoinsEngine", "com.example.CoinsEngine", "1.0",
                List.of(), null, null, null, null);
        VersionResolver resolver = new VersionResolver(ServerProfile.of("1.20.4", Set.of("paper"), 17), false);

        UpdateCandidate result = pipeline.resolvePipeline(identity, resolver);

        assertEquals(UpdateStatus.NETWORK_ERROR, result.status());
        assertEquals(1, pinned.identifyCalls.get());
        assertEquals(0, fallback.identifyCalls.get());
    }

    @Test
    void pinnedSourceIsRejectedAfterTheInstalledJarChanges() {
        PluginIdentity identity = new PluginIdentity("CoinsEngine", "com.example.CoinsEngine", "1.0",
                List.of(), null, null, "new-hash", new File("plugins/CoinsEngine.jar"));
        SourceCatalog.CatalogSource pinned = SourceCatalog.pinnedInstallation(
                "modrinth", "coins-engine", null, "1.0", "old-hash", 100L, "CoinsEngine.jar"
        );

        assertFalse(UpdatePipeline.pinnedArtifactMatches(identity, pinned));
    }

    @Test
    void recordedArtifactVersionOverridesStalePluginDescriptor(@TempDir Path tempDir) {
        File catalogFile = tempDir.resolve("sources-custom.yml").toFile();
        assertTrue(SourceCatalog.writeUserEntry(catalogFile, "EssentialsPay", "com.example.EssentialsPay",
                SourceCatalog.pinnedInstallation("spigot", "137860", null,
                        "2026-08-11", "installed-hash", 24496L, "EssentialsPay.jar")));

        FakeSource spigot = new FakeSource("spigot", List.of(remote("spigot", "137860", "2026-08-11")));
        UpdatePipeline pipeline = new UpdatePipeline(null, List.of(spigot), null, null, new SourceCatalog(catalogFile));
        PluginIdentity identity = new PluginIdentity("EssentialsPay", "com.example.EssentialsPay", "1.0-SNAPSHOT",
                List.of(), null, null, "installed-hash", new File("plugins/EssentialsPay.jar"));
        VersionResolver resolver = new VersionResolver(ServerProfile.of("1.20.4", Set.of("paper"), 17), false);

        UpdateCandidate result = pipeline.resolvePipeline(identity, resolver);

        assertEquals(UpdateStatus.UP_TO_DATE, result.status());
    }

    @Test
    void recordedArtifactVersionDoesNotHideNewerRelease(@TempDir Path tempDir) {
        File catalogFile = tempDir.resolve("sources-custom.yml").toFile();
        assertTrue(SourceCatalog.writeUserEntry(catalogFile, "EssentialsPay", "com.example.EssentialsPay",
                SourceCatalog.pinnedInstallation("spigot", "137860", null,
                        "2026-08-11", "installed-hash", 24496L, "EssentialsPay.jar")));

        FakeSource spigot = new FakeSource("spigot", List.of(remote("spigot", "137860", "2026-09-01")));
        UpdatePipeline pipeline = new UpdatePipeline(null, List.of(spigot), null, null, new SourceCatalog(catalogFile));
        PluginIdentity identity = new PluginIdentity("EssentialsPay", "com.example.EssentialsPay", "1.0-SNAPSHOT",
                List.of(), null, null, "installed-hash", new File("plugins/EssentialsPay.jar"));
        VersionResolver resolver = new VersionResolver(ServerProfile.of("1.20.4", Set.of("paper"), 17), false);

        UpdateCandidate result = pipeline.resolvePipeline(identity, resolver);

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.status());
    }

    @Test
    void recordedArtifactVersionIsIgnoredWithoutInstalledHash(@TempDir Path tempDir) {
        File catalogFile = tempDir.resolve("sources-custom.yml").toFile();
        assertTrue(SourceCatalog.writeUserEntry(catalogFile, "EssentialsPay", "com.example.EssentialsPay",
                SourceCatalog.pinnedInstallation("spigot", "137860", null,
                        "2026-08-11", "installed-hash", 24496L, "EssentialsPay.jar")));

        FakeSource spigot = new FakeSource("spigot", List.of(remote("spigot", "137860", "2026-08-11")));
        UpdatePipeline pipeline = new UpdatePipeline(null, List.of(spigot), null, null, new SourceCatalog(catalogFile));
        PluginIdentity identity = new PluginIdentity("EssentialsPay", "com.example.EssentialsPay", "1.0-SNAPSHOT",
                List.of(), null, null, null, new File("plugins/EssentialsPay.jar"));
        VersionResolver resolver = new VersionResolver(ServerProfile.of("1.20.4", Set.of("paper"), 17), false);

        UpdateCandidate result = pipeline.resolvePipeline(identity, resolver);

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.status());
    }

    private static RemoteVersion remote(String source, String ref) {
        return remote(source, ref, "2.0");
    }

    private static RemoteVersion remote(String source, String ref, String version) {
        return new RemoteVersion(source, ref, "https://example.com/" + ref, version,
                UpdateModels.ReleaseChannel.RELEASE, Set.of("1.20.4"), Set.of("paper"),
                "https://example.com/plugin.jar", "CoinsEngine.jar", null, null, 100L, Instant.now());
    }

    private static final class FakeSource implements UpdateSource {
        private final String id;
        private final List<RemoteVersion> versions;
        private final AtomicInteger identifyCalls = new AtomicInteger();

        private FakeSource(String id, List<RemoteVersion> versions) {
            this.id = id;
            this.versions = versions;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean supportsAutoInstall() {
            return true;
        }

        @Override
        public Map<String, ProjectMatch> identifyBatch(List<PluginIdentity> identities) {
            return Map.of();
        }

        @Override
        public ProjectMatch identifyFromCatalog(PluginIdentity identity, String ref, Map<String, String> options) {
            identifyCalls.incrementAndGet();
            return new ProjectMatch(identity.pluginName(), ref, "https://example.com/" + ref,
                    MatchConfidence.CONFIRMED, MatchReason.CATALOG, null);
        }

        @Override
        public List<RemoteVersion> listVersions(ProjectMatch match) {
            return versions;
        }
    }
}
