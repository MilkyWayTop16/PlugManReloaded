package ru.milkyway.plugmanreloaded.update.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DirectSourceTest {

    @Test
    @DisplayName("parseCsvSet safely handles spaces, duplicates, and empty tokens without throwing IllegalArgumentException")
    void parseCsvSetHandlesSpacesAndDuplicates() throws Exception {
        Method method = DirectSource.class.getDeclaredMethod("parseCsvSet", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) method.invoke(null, " paper, purpur, PAPER , , folia ");

        assertEquals(3, result.size());
        assertTrue(result.contains("paper"));
        assertTrue(result.contains("purpur"));
        assertTrue(result.contains("folia"));

        @SuppressWarnings("unchecked")
        Set<String> emptyResult = (Set<String>) method.invoke(null, (String) null);
        assertNotNull(emptyResult);
        assertTrue(emptyResult.isEmpty());

        @SuppressWarnings("unchecked")
        Set<String> blankResult = (Set<String>) method.invoke(null, "   ");
        assertNotNull(blankResult);
        assertTrue(blankResult.isEmpty());
    }
}
