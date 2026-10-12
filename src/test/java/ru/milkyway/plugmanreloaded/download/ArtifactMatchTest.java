package ru.milkyway.plugmanreloaded.download;

import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ArtifactMatchTest {

    @Test
    void testRaycastedAntiEspArtifactMatching() {
        DownloadModels.SearchResultEntry entry = new DownloadModels.SearchResultEntry(
                "modrinth", "raycasted-anti-esp", "Raycasted Anti-ESP", "Author", "1.0", "", "url", null,
                100, 10, 100.0, List.of(), List.of("paper"), List.of(), null, null, null, false, true
        );
        boolean matches = PluginDownloader.artifactMatchesExpectedProject(entry, "RaycastedAntiESP");
        assertTrue(matches, "RaycastedAntiESP should match Raycasted Anti-ESP");
    }

    @Test
    void testLiveSearchModrinthRaycasted() {
        PluginSearch search = new PluginSearch(null, ServerProfile.of("1.21.11", Set.of("paper")), new SourceCatalog(null));
        List<DownloadModels.SearchResultEntry> hits = search.search("raycasted-anti-esp", "modrinth", 1);
        assertNotNull(hits);
        assertFalse(hits.isEmpty(), "Should find raycasted-anti-esp on modrinth");
        DownloadModels.SearchResultEntry hit = hits.get(0);
        System.out.println("HIT: projectId=" + hit.projectId() + ", title=" + hit.title() + ", sourceId=" + hit.sourceId());
        boolean matches = PluginDownloader.artifactMatchesExpectedProject(hit, "RaycastedAntiESP");
        assertTrue(matches, "Hit should match RaycastedAntiESP");
    }
}
