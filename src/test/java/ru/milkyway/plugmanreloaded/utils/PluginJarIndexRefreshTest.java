package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginJarIndexRefreshTest {

    @Test
    void jarCountChangeTriggersRefreshEvenWhenDirModifiedDidNotChange() {

        assertTrue(PluginJarIndex.shouldRefresh(
                1_000L, 21,
                1_000L, 20,
                500_000L,
                500_100L, 5_000L));
    }

    @Test
    void jarCountDropTriggersRefreshToo() {
        assertTrue(PluginJarIndex.shouldRefresh(
                1_000L, 19,
                1_000L, 20,
                500_000L,
                500_100L, 5_000L));
    }

    @Test
    void nothingChangedAndNotExpiredMeansNoRefresh() {
        assertFalse(PluginJarIndex.shouldRefresh(
                1_000L, 20,
                1_000L, 20,
                500_000L,
                502_000L, 5_000L));
    }

    @Test
    void ttlExpiryTriggersRefreshEvenWithoutAnyChange() {
        assertTrue(PluginJarIndex.shouldRefresh(
                1_000L, 20,
                1_000L, 20,
                500_000L,
                505_001L, 5_000L));
    }

    @Test
    void exactlyAtTtlBoundaryDoesNotYetExpire() {
        assertFalse(PluginJarIndex.shouldRefresh(
                1_000L, 20,
                1_000L, 20,
                500_000L,
                505_000L, 5_000L));
    }

    @Test
    void dirModifiedChangeAloneAlsoTriggersRefresh() {
        assertTrue(PluginJarIndex.shouldRefresh(
                2_000L, 20,
                1_000L, 20,
                500_000L,
                500_100L, 5_000L));
    }

    @Test
    void diskIsNotCheckedMoreOftenThanTheThrottleInterval() {
        assertFalse(PluginJarIndex.shouldCheckDisk(1_500L, 1_000L, 1_000L));
        assertTrue(PluginJarIndex.shouldCheckDisk(2_000L, 1_000L, 1_000L));
        assertTrue(PluginJarIndex.shouldCheckDisk(2_001L, 1_000L, 1_000L));
    }
}
