package ru.milkyway.plugmanreloaded;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.UpdateCache;
import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.UpdateModels.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.VersionCompare;
import ru.milkyway.plugmanreloaded.update.VersionResolver;
import ru.milkyway.plugmanreloaded.update.source.GithubSource;
import ru.milkyway.plugmanreloaded.update.source.SpigotSource;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import ru.milkyway.plugmanreloaded.utils.JarValidator;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

public class PluginJarInspectionIntegrationTest {

    @Test
    void testCompiledShadedJarStructure() throws IOException {
        File targetJar = new File("target/PlugManReloaded-1.1-beta.jar");
        assertTrue(targetJar.exists(), "target/PlugManReloaded-1.1-beta.jar must exist");
        assertTrue(targetJar.length() > 500_000, "Shaded jar size must be > 500KB");

        assertTrue(JarValidator.isValidPluginJar(targetJar));
        assertEquals("PlugManReloaded", JarValidator.readPluginName(targetJar));

        try (JarFile jf = new JarFile(targetJar)) {
            assertNotNull(jf.getEntry("plugin.yml"));
            assertNotNull(jf.getEntry("ru/milkyway/plugmanreloaded/PlugManReloaded.class"));
            assertNotNull(jf.getEntry("ru/milkyway/plugmanreloaded/update/VersionCompare.class"));
            assertNotNull(jf.getEntry("ru/milkyway/plugmanreloaded/update/source/SpigotSource.class"));
            assertNotNull(jf.getEntry("ru/milkyway/plugmanreloaded/update/source/GithubSource.class"));
            assertNotNull(jf.getEntry("ru/milkyway/plugmanreloaded/utils/bstats/bukkit/Metrics.class"));
        }
    }

    @Test
    void testRealPluginJarsInspection() {
        File pluginsDir = new File("C:/Users/Stas/Desktop/HellMine (19.01.2026)/plugins");
        if (!pluginsDir.isDirectory()) return;

        File essentialsJar = new File(pluginsDir, "EssentialsX.jar");
        if (essentialsJar.exists()) {
            JarValidator.PreFlightReport report = JarValidator.validatePreFlight(essentialsJar, "Essentials", false);
            assertTrue(report.isValid());
            assertEquals("Essentials", report.declaredName());
            assertNotNull(report.declaredVersion());
        }

        File luckPermsJar = new File(pluginsDir, "LuckPerms.jar");
        if (luckPermsJar.exists()) {
            JarValidator.PreFlightReport report = JarValidator.validatePreFlight(luckPermsJar, "LuckPerms", false);
            assertTrue(report.isValid());
            assertEquals("LuckPerms", report.declaredName());
        }
    }

    @Test
    void testSpigot137860TestedVersionsOnBoth1165And12111() {
        UpdateCache cache = new UpdateCache(60_000L);
        SpigotSource source = new SpigotSource(cache);
        PluginIdentity identity = new PluginIdentity("RaycastedAntiESP", "com.example.AntiESP", "0.9",
                List.of("Author"), null, null, null, new File("RaycastedAntiESP.jar"));
        UpdateSource.ProjectMatch match = source.identifyFromCatalog(identity, "137860", Map.of());
        assertNotNull(match);

        List<RemoteVersion> versions = source.listVersions(match);
        Assumptions.assumeFalse(versions.isEmpty(), "Spiget unavailable");

        RemoteVersion latest = versions.get(0);
        assertFalse(latest.gameVersions().isEmpty(), "gameVersions must not be empty after SpigotSource fix");
        assertTrue(latest.gameVersions().contains("1.21"), "Spigot 137860 has 1.21 in testedVersions");

        assertFalse(VersionCompare.supportsGameVersion(latest.gameVersions(), "1.16.5"),
                "Spigot 137860 only targets 1.21+ so 1.16.5 should be rejected");
        assertTrue(VersionCompare.supportsGameVersion(latest.gameVersions(), "1.21.11"),
                "Spigot 137860 tested for 1.21 must support 1.21.11");

        assertTrue(VersionCompare.supportsGameVersion(Set.of("1.16 - 1.21"), "1.16.5"),
                "Range 1.16 - 1.21 must support 1.16.5");
        assertTrue(VersionCompare.supportsGameVersion(Set.of("1.16 - 1.21"), "1.21.11"),
                "Range 1.16 - 1.21 must support 1.21.11");

        ServerProfile profile12111 = ServerProfile.of("1.21.11", Set.of("paper", "spigot", "bukkit"));
        UpdateCandidate candidate12111 = new VersionResolver(profile12111, false).resolve(identity, match, versions);
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, candidate12111.status(), "Must offer update on Leaves 1.21.11");
    }

    @Test
    void testGithubSourceMetadataExtractionOnRealJarRelease() {
        UpdateCache cache = new UpdateCache(60_000L);
        GithubSource source = new GithubSource(cache, null);
        PluginIdentity identity = new PluginIdentity("MaceAndSpearFix", "ru.milkyway.maceandspearfix.MaceAndSpearFix", "1.0",
                List.of("MilkyWay"), null, null, null, new File("MaceAndSpearFix.jar"));
        UpdateSource.ProjectMatch match = source.identifyFromCatalog(identity, "Milk-Way/MaceAndSpearFix", Map.of());
        assertNotNull(match);

        List<RemoteVersion> versions = source.listVersions(match);
        Assumptions.assumeFalse(versions.isEmpty(), "GitHub API unavailable or rate-limited");

        RemoteVersion latest = versions.get(0);
        assertFalse(latest.loaders().isEmpty(), "GithubSource must assign default loaders");
        assertTrue(latest.loaders().contains("paper"));
        assertTrue(latest.loaders().contains("purpur"));
    }
}
