package ru.milkyway.plugmanreloaded.commands.sub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadCommandVersionFormatTest {

    private String formatVersion(String rawVersion, String versionLatest) {
        String rawVer = rawVersion != null && !rawVersion.isBlank() ? rawVersion : "";
        return !rawVer.isBlank() ? "v" + PluginMetaHelper.cleanVersion(rawVer) : versionLatest;
    }

    @Test
    @DisplayName("FormatVersion prepends v to clean version numbers")
    void formatVersionPrependsVToNumericVersions() {
        assertEquals("v1.2.3", formatVersion("1.2.3", "Последняя"));
        assertEquals("v2.16.6", formatVersion("v2.16.6", "Последняя"));
        assertEquals("v0.9.1-SNAPSHOT", formatVersion("0.9.1-SNAPSHOT", "Последняя"));
    }

    @Test
    @DisplayName("FormatVersion returns localized latest string without prepending v when raw version is blank or null")
    void formatVersionReturnsLatestWithoutV() {
        assertEquals("Последняя", formatVersion("", "Последняя"));
        assertEquals("Последняя", formatVersion("   ", "Последняя"));
        assertEquals("Последняя", formatVersion(null, "Последняя"));
        assertEquals("Latest", formatVersion("", "Latest"));
        assertEquals("Latest", formatVersion(null, "Latest"));
    }
}
