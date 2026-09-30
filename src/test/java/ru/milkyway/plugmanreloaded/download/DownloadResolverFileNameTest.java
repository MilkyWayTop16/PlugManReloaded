package ru.milkyway.plugmanreloaded.download;

import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.download.DownloadModels.SearchResultEntry;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DownloadResolverFileNameTest {

    @Test
    void testSanitizeFileNameInResolvedDownload() {
        DownloadResolver resolver = new DownloadResolver(null, null);

        SearchResultEntry entryWithPipes = new SearchResultEntry(
                "spigot", "105658", "LuckPerms | Permissions", "Luck", "5.4.145",
                "Permissions plugin", "https://spigotmc.org/resources/105658", null,
                100, 10, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        DownloadResolver.DownloadResolution resolution = resolver.resolve(entryWithPipes);
        assertNull(resolution.failureDetail());
        assertNotNull(resolution.info());
        assertEquals("LuckPerms___Permissions.jar", resolution.info().fileName());
    }

    @Test
    void testSanitizeFileNameWithNullOrBlankTitle() {
        DownloadResolver resolver = new DownloadResolver(null, null);

        SearchResultEntry nullTitle = new SearchResultEntry(
                "spigot", "105658", null, "Author", "1.0",
                "Description", "https://spigotmc.org/resources/105658", null,
                100, 10, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        DownloadResolver.DownloadResolution resolution = resolver.resolve(nullTitle);
        assertNull(resolution.failureDetail());
        assertNotNull(resolution.info());
        assertEquals("plugin.jar", resolution.info().fileName());

        SearchResultEntry blankTitle = new SearchResultEntry(
                "spigot", "105658", "   ", "Author", "1.0",
                "Description", "https://spigotmc.org/resources/105658", null,
                100, 10, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        DownloadResolver.DownloadResolution blankResolution = resolver.resolve(blankTitle);
        assertNull(blankResolution.failureDetail());
        assertNotNull(blankResolution.info());
        assertEquals("plugin.jar", blankResolution.info().fileName());
    }
}
