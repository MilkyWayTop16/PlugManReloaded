package ru.milkyway.plugmanreloaded.update;

import ru.milkyway.plugmanreloaded.update.UpdateModels.*;

import ru.milkyway.plugmanreloaded.BukkitServerMock;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.update.source.ModrinthSource;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class UpdateServiceTest {

    @BeforeAll
    static void setUp() {
        BukkitServerMock.ensureInitialized();
    }

    private static UpdateService createTestService() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        UpdateService service = (UpdateService) unsafe.allocateInstance(UpdateService.class);

        Field cacheField = UpdateService.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        cacheField.set(service, new UpdateCache(3600_000L));

        Field sourcesField = UpdateService.class.getDeclaredField("sources");
        sourcesField.setAccessible(true);
        sourcesField.set(service, new ArrayList<>());

        Field executorField = UpdateService.class.getDeclaredField("resolveExecutor");
        executorField.setAccessible(true);
        executorField.set(service, Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "PlugManReloaded-test-update");
            t.setDaemon(true);
            return t;
        }));

        return service;
    }

    @Test
    @DisplayName("Verify getSource finds registered sources case-insensitively")
    void testGetSource() throws Exception {
        UpdateService service = createTestService();
        Field sourcesField = UpdateService.class.getDeclaredField("sources");
        sourcesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<UpdateSource> sources = (List<UpdateSource>) sourcesField.get(service);

        UpdateSource modrinth = new ModrinthSource(new UpdateCache(3600_000L));
        sources.add(modrinth);

        assertNull(service.getSource(null));
        assertNull(service.getSource("nonexistent"));
        assertSame(modrinth, service.getSource("modrinth"));
        assertSame(modrinth, service.getSource("MODRINTH"));
        assertNull(service.getRecentAllResults());
    }

    @Test
    @DisplayName("Verify clearVersionsCache completely resets state and notification counters")
    void testClearVersionsCacheResetsAllState() throws Exception {
        UpdateService service = createTestService();

        Field lastAvailableField = UpdateService.class.getDeclaredField("lastAvailableCount");
        lastAvailableField.setAccessible(true);
        lastAvailableField.setInt(service, 5);

        Field lastTotalField = UpdateService.class.getDeclaredField("lastTotalCount");
        lastTotalField.setAccessible(true);
        lastTotalField.setInt(service, 10);

        Field initialCheckedField = UpdateService.class.getDeclaredField("initialChecked");
        initialCheckedField.setAccessible(true);
        initialCheckedField.setBoolean(service, true);

        assertEquals(5, lastAvailableField.getInt(service));
        assertEquals(10, lastTotalField.getInt(service));
        assertTrue(initialCheckedField.getBoolean(service));

        service.clearVersionsCache();

        assertEquals(0, lastAvailableField.getInt(service));
        assertEquals(0, lastTotalField.getInt(service));
        assertFalse(initialCheckedField.getBoolean(service));
        assertNull(service.getRecentAllResults());
    }

    @Test
    @DisplayName("Verify runCheck updates lastAvailableCount, lastTotalCount and initialChecked for onPlayerJoin notifications")
    void testRunCheckUpdatesNotificationCounters() throws Exception {
        UpdateService service = createTestService();

        PluginIdentity id1 = new PluginIdentity("Plugin1", "org.test.P1", "1.0", List.of(), null, null, null, null);
        PluginIdentity id2 = new PluginIdentity("Plugin2", "org.test.P2", "1.0", List.of(), null, null, null, null);

        UpdateCandidate c1 = new UpdateCandidate(id1, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG, UpdateStatus.UPDATE_AVAILABLE, null);
        UpdateCandidate c2 = UpdateCandidate.upToDate(id2, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG);

        service.recordCheckResults(List.of(c1, c2), 1);

        Field initialCheckedField = UpdateService.class.getDeclaredField("initialChecked");
        initialCheckedField.setAccessible(true);
        assertTrue(initialCheckedField.getBoolean(service));

        Field lastAvailableField = UpdateService.class.getDeclaredField("lastAvailableCount");
        lastAvailableField.setAccessible(true);
        assertEquals(1, lastAvailableField.getInt(service));

        Field lastTotalField = UpdateService.class.getDeclaredField("lastTotalCount");
        lastTotalField.setAccessible(true);
        assertEquals(2, lastTotalField.getInt(service));

        assertNotNull(service.getRecentAllResults());
        assertEquals(2, service.getRecentAllResults().size());
    }

    @Test
    @DisplayName("Verify single-plugin check updates existing lastAllResults cache entry")
    void testSinglePluginCheckUpdatesExistingLastAllResults() throws Exception {
        UpdateService service = createTestService();

        PluginIdentity id1 = new PluginIdentity("Plugin1", "org.test.P1", "1.0", List.of(), null, null, null, null);
        PluginIdentity id2 = new PluginIdentity("Plugin2", "org.test.P2", "1.0", List.of(), null, null, null, null);

        UpdateCandidate c1Old = UpdateCandidate.upToDate(id1, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG);
        UpdateCandidate c2 = UpdateCandidate.upToDate(id2, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG);

        service.recordCheckResults(List.of(c1Old, c2), 0);

        Field lastAvailableField = UpdateService.class.getDeclaredField("lastAvailableCount");
        lastAvailableField.setAccessible(true);
        assertEquals(0, lastAvailableField.getInt(service));
        assertEquals(c1Old, service.getRecentAllResults().get(0));

        UpdateCandidate c1New = new UpdateCandidate(id1, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG, UpdateStatus.UPDATE_AVAILABLE, null);
        service.recordCheckResults(List.of(c1New), 1);

        assertEquals(1, lastAvailableField.getInt(service));
        assertEquals(c1New, service.getRecentAllResults().get(0));
    }

    @Test
    @DisplayName("Verify resolvePipeline compares candidates across all stages using CANDIDATE_COMPARATOR")
    void testResolvePipelineStageComparisons() {
        PluginIdentity id = new PluginIdentity("Plugin1", "org.test.P1", "1.0", List.of(), null, null, null, null);

        UpdateCandidate weak = new UpdateCandidate(id, null, MatchConfidence.WEAK, MatchReason.NONE, UpdateStatus.NO_SOURCE, null);
        UpdateCandidate likely = new UpdateCandidate(id, null, MatchConfidence.LIKELY, MatchReason.CATALOG, UpdateStatus.UP_TO_DATE, null);
        UpdateCandidate confirmed = new UpdateCandidate(id, null, MatchConfidence.CONFIRMED, MatchReason.MAIN_MATCH, UpdateStatus.UPDATE_AVAILABLE, null);

        assertTrue(UpdateCandidateComparator.INSTANCE.compare(confirmed, likely) > 0);
        assertTrue(UpdateCandidateComparator.INSTANCE.compare(likely, weak) > 0);
        assertTrue(UpdateCandidateComparator.INSTANCE.compare(confirmed, weak) > 0);
    }

    @Test
    @DisplayName("Verify getRecentAllResults returns an unmodifiable list")
    void testRecentAllResultsUnmodifiability() throws Exception {
        UpdateService service = createTestService();

        PluginIdentity id = new PluginIdentity("Plugin1", "org.test.P1", "1.0", List.of(), null, null, null, null);
        UpdateCandidate cand = UpdateCandidate.upToDate(id, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG);

        service.recordCheckResults(List.of(cand, cand), 0);

        List<UpdateCandidate> recent = service.getRecentAllResults();
        assertNotNull(recent);
        assertEquals(2, recent.size());

        assertThrows(UnsupportedOperationException.class, () -> recent.add(cand));
        assertThrows(UnsupportedOperationException.class, () -> recent.remove(0));
        assertThrows(UnsupportedOperationException.class, recent::clear);
    }

    @Test
    @DisplayName("Verify resolveInParallel handles multiple plugins and concurrent invocations")
    void testResolveInParallelConcurrentInvocations() throws Exception {
        UpdateService service = createTestService();
        try {
            int count = 8;
            List<PluginIdentity> pending1 = new ArrayList<>();
            List<PluginIdentity> pending2 = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                pending1.add(new PluginIdentity("PluginA" + i, "org.test.A" + i, "1.0", List.of(), null, null, null, null));
                pending2.add(new PluginIdentity("PluginB" + i, "org.test.B" + i, "1.0", List.of(), null, null, null, null));
            }

            List<UpdateCandidate> results1 = Collections.synchronizedList(new ArrayList<>());
            List<UpdateCandidate> results2 = Collections.synchronizedList(new ArrayList<>());

            CountDownLatch latch = new CountDownLatch(2);
            AtomicBoolean failed = new AtomicBoolean(false);

            Thread t1 = new Thread(() -> {
                try {
                    List<PluginIdentity> still = service.resolveInParallel(pending1, results1, id ->
                            UpdateCandidate.upToDate(id, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG));
                    if (!still.isEmpty()) failed.set(true);
                } catch (Exception e) {
                    failed.set(true);
                } finally {
                    latch.countDown();
                }
            });

            Thread t2 = new Thread(() -> {
                try {
                    List<PluginIdentity> still = service.resolveInParallel(pending2, results2, id ->
                            UpdateCandidate.upToDate(id, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG));
                    if (!still.isEmpty()) failed.set(true);
                } catch (Exception e) {
                    failed.set(true);
                } finally {
                    latch.countDown();
                }
            });

            t1.start();
            t2.start();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertFalse(failed.get());
            assertEquals(count, results1.size());
            assertEquals(count, results2.size());
        } finally {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("Verify resolveInParallel supports repeated check iterations without leaking")
    void testResolveInParallelRepeatedChecks() throws Exception {
        UpdateService service = createTestService();
        try {
            for (int round = 0; round < 5; round++) {
                List<PluginIdentity> pending = List.of(
                        new PluginIdentity("RoundP1_" + round, "org.test.P1", "1.0", List.of(), null, null, null, null),
                        new PluginIdentity("RoundP2_" + round, "org.test.P2", "1.0", List.of(), null, null, null, null),
                        new PluginIdentity("RoundP3_" + round, "org.test.P3", "1.0", List.of(), null, null, null, null)
                );
                List<UpdateCandidate> results = new ArrayList<>();
                List<PluginIdentity> still = service.resolveInParallel(pending, results, id ->
                        UpdateCandidate.upToDate(id, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG));

                assertTrue(still.isEmpty());
                assertEquals(3, results.size());
            }
        } finally {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("Verify shutdown during resolveInParallel execution terminates workers cleanly")
    void testResolveInParallelShutdownDuringExecution() throws Exception {
        UpdateService service = createTestService();
        List<PluginIdentity> pending = List.of(
                new PluginIdentity("SlowPlugin1", "org.test.Slow1", "1.0", List.of(), null, null, null, null),
                new PluginIdentity("SlowPlugin2", "org.test.Slow2", "1.0", List.of(), null, null, null, null),
                new PluginIdentity("SlowPlugin3", "org.test.Slow3", "1.0", List.of(), null, null, null, null)
        );

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        List<UpdateCandidate> results = new ArrayList<>();

        Thread worker = new Thread(() -> {
            try {
                service.resolveInParallel(pending, results, id -> {
                    started.countDown();
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } finally {
                done.countDown();
            }
        });

        worker.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        service.shutdown();
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(service.isShutdown());
    }

    @Test
    @DisplayName("Verify exceptions in worker tasks are captured and identities preserved in stillPending")
    void testResolveInParallelWorkerTaskExceptions() throws Exception {
        UpdateService service = createTestService();
        try {
            PluginIdentity goodId = new PluginIdentity("GoodPlugin", "org.test.Good", "1.0", List.of(), null, null, null, null);
            PluginIdentity badId = new PluginIdentity("BadPlugin", "org.test.Bad", "1.0", List.of(), null, null, null, null);
            List<PluginIdentity> pending = List.of(goodId, badId);

            List<UpdateCandidate> results = new ArrayList<>();
            List<PluginIdentity> still = service.resolveInParallel(pending, results, id -> {
                if (id.pluginName().equals("BadPlugin")) {
                    throw new RuntimeException("Simulated API failure");
                }
                return UpdateCandidate.upToDate(id, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG);
            });

            assertEquals(1, results.size());
            assertEquals("GoodPlugin", results.get(0).identity().pluginName());
            assertEquals(1, still.size());
            assertEquals("BadPlugin", still.get(0).pluginName());
        } finally {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("Verify no lingering threads and safe rejection after shutdown")
    void testAbsenceOfLingeringThreadsAfterShutdown() throws Exception {
        UpdateService service = createTestService();
        List<PluginIdentity> pending = List.of(
                new PluginIdentity("P1", "org.test.P1", "1.0", List.of(), null, null, null, null),
                new PluginIdentity("P2", "org.test.P2", "1.0", List.of(), null, null, null, null)
        );

        List<UpdateCandidate> results = new ArrayList<>();
        service.resolveInParallel(pending, results, id ->
                UpdateCandidate.upToDate(id, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG));
        assertEquals(2, results.size());

        service.shutdown();
        assertTrue(service.isShutdown());
        assertTrue(service.getResolveExecutor().isTerminated());

        List<UpdateCandidate> afterShutdownResults = new ArrayList<>();
        List<PluginIdentity> returnedPending = service.resolveInParallel(pending, afterShutdownResults, id ->
                UpdateCandidate.upToDate(id, null, MatchConfidence.CONFIRMED, MatchReason.CATALOG));

        assertEquals(pending, returnedPending);
        assertTrue(afterShutdownResults.isEmpty());
    }

    @Test
    @DisplayName("Verify snapshotLoadedPlugins filters null entries safely")
    void testSnapshotLoadedPluginsNullSafety() throws Exception {
        UpdateService service = createTestService();
        List<Plugin> plugins = service.snapshotLoadedPlugins();
        assertNotNull(plugins);
        for (Plugin p : plugins) {
            assertNotNull(p);
        }
    }
}
