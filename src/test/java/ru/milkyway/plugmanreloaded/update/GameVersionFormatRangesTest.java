package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GameVersionFormatRangesTest {

    @Test
    public void testEmptyAndNull() {
        assertEquals("Any", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(null));
        assertEquals("Any", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(Collections.emptyList()));
        assertEquals("Any", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(List.of("", "   ")));
    }

    @Test
    public void testSingleVersion() {
        assertEquals("1.20.4", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(List.of("1.20.4")));
        assertEquals("1.21.1", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(List.of("v1.21.1")));
    }

    @Test
    public void testChunkyFullList() {
        List<String> versions = List.of(
                "1.14.4", "1.15", "1.15.1", "1.15.2", "1.16", "1.16.1", "1.16.2", "1.16.3",
                "1.16.4", "1.16.5", "1.17", "1.17.1", "1.18", "1.18.1", "1.18.2", "1.19",
                "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20", "1.20.1", "1.20.2", "1.20.3",
                "1.20.4", "1.20.5", "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
                "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
                "26.1", "26.1.1", "26.1.2", "26.2"
        );
        assertEquals("1.14.4 \u2013 1.21.11, 26.1 \u2013 26.2", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(versions));
    }

    @Test
    public void testDisjointRanges() {
        List<String> versions = List.of("1.8.8", "1.12.2", "1.16.4", "1.16.5", "1.17", "1.17.1");
        assertEquals("1.8.8, 1.12.2, 1.16.4 – 1.17.1", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(versions));
    }

    @Test
    public void testMinorProgression() {
        List<String> versions = List.of("1.16.5", "1.17.1", "1.18.2", "1.19.4", "1.20.4", "1.21.1");
        assertEquals("1.16.5 – 1.21.1", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(versions));
    }

    @Test
    public void testUnsortedAndDuplicates() {
        List<String> versions = List.of("1.21", "1.16.5", "1.20.4", "1.16.5", "1.21", "1.19.4");
        assertEquals("1.16.5, 1.19.4 – 1.21", ru.milkyway.plugmanreloaded.utils.GameVersionFormatter.formatRanges(versions));
    }
}
