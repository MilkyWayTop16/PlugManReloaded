package ru.milkyway.plugmanreloaded.commands.sub;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageCommandTest {

    @Test
    void displayCommandNeverDoublesTheLeadingSlash() {
        assertEquals("/help", UsageCommand.formatDisplayCommand("help"));
        assertEquals("/help", UsageCommand.formatDisplayCommand("/help"));
        assertEquals("/help", UsageCommand.formatDisplayCommand(UsageCommand.formatDisplayCommand("/help")));
    }

    @Test
    void matchScoreRanksExactMatchAboveSegmentMatch() throws Exception {
        Method matchScore = UsageCommand.class.getDeclaredMethod("matchScore", String.class, String.class);
        matchScore.setAccessible(true);

        int exact = (int) matchScore.invoke(null, "plugmanreloaded.download", "plugmanreloaded.download");
        int prefix = (int) matchScore.invoke(null, "plugmanreloaded.download", "plugmanreloaded.down");
        int segment = (int) matchScore.invoke(null, "plugmanreloaded.download", "download");
        int contains = (int) matchScore.invoke(null, "plugmanreloaded.download", "load");
        int none = (int) matchScore.invoke(null, "plugmanreloaded.download", "essentials");

        assertEquals(100, exact);
        assertTrue(prefix > 0 && prefix < exact);
        assertTrue(segment > 0 && segment < prefix);
        assertTrue(contains > 0 && contains < segment);
        assertEquals(-1, none);
    }
}
