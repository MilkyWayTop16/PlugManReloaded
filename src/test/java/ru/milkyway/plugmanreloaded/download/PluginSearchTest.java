package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.update.ServerProfile;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PluginSearchTest {

    @Test
    public void testRelevanceScoreCalculations() {
        PluginSearch engine = new PluginSearch(null, null, null);

        SearchResultEntry exact = new SearchResultEntry(
                "modrinth", "luckperms", "LuckPerms", "Luck", "5.4.145",
                "A permissions plugin", "https://modrinth.com/plugin/luckperms", null,
                2_500_000, 1500, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        SearchResultEntry addon = new SearchResultEntry(
                "modrinth", "luckperms-addon", "LuckPerms GUI Addon", "Someone", "1.0",
                "GUI extension for LuckPerms", "https://modrinth.com/plugin/luckperms-addon", null,
                500, 10, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        double exactScore = engine.computeRelevanceScore("LuckPerms", exact);
        double addonScore = engine.computeRelevanceScore("LuckPerms", addon);

        assertTrue(exactScore > addonScore, "Exact match score (" + exactScore + ") should be higher than addon score (" + addonScore + ")");
        assertTrue(exactScore >= 50.0);
    }

    @Test
    public void testGithubFabricModIsPenalizedAgainstGenuinePlugin() {
        PluginSearch engine = new PluginSearch(null, null, null);

        SearchResultEntry genuinePlugin = new SearchResultEntry(
                "github", "someauthor/Chunky", "Chunky", "someauthor", "",
                "Pre-generates chunks for your Paper server", "https://github.com/someauthor/Chunky", null,
                0, 40, 0.0, Collections.emptyList(), List.of("paper-plugin"), Collections.emptyList(),
                null, null, null, false, true
        );

        SearchResultEntry fabricMod = new SearchResultEntry(
                "github", "someauthor/chunky-loader", "Chunky Loader", "someauthor", "",
                "A client-side mod that speeds up chunk loading", "https://github.com/someauthor/chunky-loader", null,
                0, 200, 0.0, Collections.emptyList(), List.of("fabric", "minecraft-mod"), Collections.emptyList(),
                null, null, null, false, true
        );

        double pluginScore = engine.computeRelevanceScore("Chunky", genuinePlugin);
        double modScore = engine.computeRelevanceScore("Chunky", fabricMod);

        assertTrue(pluginScore > modScore,
                "GitHub-репозиторий с топиком fabric и без plugin-контекста обязан получить штраф "
                        + "относительно настоящего Paper-плагина (plugin=" + pluginScore + ", mod=" + modScore + "). "
                        + "Раньше loaders для КАЖДОГО GitHub-результата принудительно содержал \"paper\"/\"spigot\", "
                        + "из-за чего hasPluginWord был истинным всегда и штраф не мог сработать никогда");
    }

    @Test
    public void testGithubPluginWithoutTopicsIsNotFalselyPenalized() {
        PluginSearch engine = new PluginSearch(null, null, null);

        SearchResultEntry plainRepo = new SearchResultEntry(
                "github", "someauthor/MyPlugin", "MyPlugin", "someauthor", "",
                "A simple Spigot plugin with no bells and whistles", "https://github.com/someauthor/MyPlugin", null,
                0, 5, 0.0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, null, null, false, true
        );

        double score = engine.computeRelevanceScore("MyPlugin", plainRepo);

        assertTrue(score >= 50.0,
                "Репозиторий без GitHub-топиков, но с упоминанием Spigot в описании и точным совпадением "
                        + "имени, не должен штрафоваться как немайнкрафтовый (score=" + score + ")");
    }

    @Test
    public void testUnrelatedMarketplaceResultsAreRejected() {
        List<SearchResultEntry> unrelated = List.of(
                entry("ordeructionsystem", "OrdeructionSystem", "Production orders plugin"),
                entry("bubbleperks", "BubblePerks", "Perks plugin"),
                entry("obfeco", "OBFEco", "Economy plugin"),
                entry("axrankmenu", "AxRankMenu", "Rank menu plugin")
        );

        assertTrue(unrelated.stream().noneMatch(entry -> PluginSearch.isRelevantSearchResult("coinsengine", entry)));
    }

    @Test
    public void knownIncompatibleGameVersionsAreRejected() {
        PluginSearch engine = new PluginSearch(null, ServerProfile.of("1.21.4", Set.of("paper"), 21), null);
        SearchResultEntry incompatible = new SearchResultEntry(
                "spigot", "11734", "DeluxeMenus", "extended_clip", "1.14.1", "", "", null,
                0, 0, 0.0, List.of("1.19", "1.20"), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
        SearchResultEntry compatible = new SearchResultEntry(
                "spigot", "11734", "DeluxeMenus", "extended_clip", "1.14.1", "", "", null,
                0, 0, 0.0, List.of("1.20", "1.21"), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        assertFalse(engine.isCompatibleWithServer(incompatible));
        assertTrue(engine.isCompatibleWithServer(compatible));
        engine.shutdown();
    }

    @Test
    public void testExactTypoAndDescriptionMatchesRemainRelevant() {
        assertTrue(PluginSearch.isRelevantSearchResult(
                "coinsengine", entry("coinsengine", "CoinsEngine", "Economy plugin")
        ));
        assertTrue(PluginSearch.isRelevantSearchResult(
                "coinsengin", entry("coinsengine", "CoinsEngine", "Economy plugin")
        ));
        assertTrue(PluginSearch.isRelevantSearchResult(
                "permissions", entry("luckperms", "LuckPerms", "A permissions plugin")
        ));
    }

    private static SearchResultEntry entry(String projectId, String title, String description) {
        return new SearchResultEntry(
                "modrinth", projectId, title, "author", "1.0", description,
                "https://modrinth.com/plugin/" + projectId, null,
                100, 1, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
    }

    @Test
    public void testLockManagerSequentialSemantics() {
        DownloadService.DownloadLocks lockManager = new DownloadService.DownloadLocks();

        assertTrue(lockManager.tryLock("Vault"));
        assertFalse(lockManager.tryLock("Vault"), "Duplicate lock should be rejected");
        assertFalse(lockManager.tryLock("vault"), "Case-insensitive duplicate lock should be rejected");

        assertTrue(lockManager.isLocked("Vault"));

        lockManager.unlock("Vault");
        assertFalse(lockManager.isLocked("Vault"));
        assertTrue(lockManager.tryLock("Vault"), "Lock should be re-acquirable after unlock");
    }

    @Test
    public void testLockManagerIsAtomicUnderContention() throws Exception {
        int rounds = 500;
        int threads = 8;

        for (int round = 0; round < rounds; round++) {
            DownloadService.DownloadLocks lockManager = new DownloadService.DownloadLocks();
            CyclicBarrier gate = new CyclicBarrier(threads);
            AtomicInteger acquired = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                        if (lockManager.tryLock("Vault")) {
                            acquired.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS), "Threads did not finish in time");
            pool.shutdownNow();

            assertEquals(1, acquired.get(),
                    "Exactly one thread must acquire the lock, got " + acquired.get() + " in round " + round);
        }
    }

    @Test
    public void testResolvedDependencyTreeSemantics() {
        SearchResultEntry target = new SearchResultEntry(
                "modrinth", "townychat", "TownyChat", "Towny", "1.0",
                "Towny chat", "https://modrinth.com/plugin/townychat", null,
                10000, 50, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        SearchResultEntry dep1 = new SearchResultEntry(
                "modrinth", "towny", "Towny", "Towny", "1.0",
                "Towny", "https://modrinth.com/plugin/towny", null,
                50000, 100, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        DependencyTree tree = new DependencyTree(
                "TownyChat", target, List.of(dep1), Collections.emptyList(),
                List.of("Vault"), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), false, null
        );

        assertTrue(tree.hasMissing());
        assertTrue(tree.isFullyResolvable());
        assertEquals(1, tree.requiredDependencies().size());
        assertEquals(1, tree.alreadySatisfied().size());
    }

    @Test
    @Tag("live")
    public void testWorldGuardLiveSearch() throws Exception {
        ru.milkyway.plugmanreloaded.update.ServerProfile profile = ru.milkyway.plugmanreloaded.update.ServerProfile.of("1.21.1", java.util.Set.of("paper", "spigot", "bukkit"), 21);
        ru.milkyway.plugmanreloaded.update.SourceCatalog catalog = new ru.milkyway.plugmanreloaded.update.SourceCatalog(new java.io.File("sources-custom.yml"));
        PluginSearch engine = new PluginSearch(null, profile, catalog);
        List<SearchResultEntry> hits = engine.search("WorldGuard", null, 5);
        Assumptions.assumeFalse(hits.isEmpty(),
                "Площадки недоступны из этой сети, живой тест поиска пропущен");

        SearchResultEntry top = hits.stream()
                .filter(h -> h.title().toLowerCase(java.util.Locale.ROOT).contains("worldguard"))
                .findFirst()
                .orElse(hits.get(0));
        assertTrue(hits.stream().anyMatch(h -> h.title().toLowerCase(java.util.Locale.ROOT).contains("worldguard")));

        PluginDownloader coordinator = new PluginDownloader(null, profile, "PlugManReloaded/1.0");
        java.nio.file.Path inspectDir = java.nio.file.Files.createTempDirectory("wg_inspect_test");
        java.io.File staged = coordinator.stageForInspection(top, inspectDir).file();
        Assumptions.assumeTrue(staged != null,
                "Файл не скачался из этой сети, живой тест установки пропущен");
        try {
            assertTrue(staged.exists());
            assertTrue(staged.length() > 500_000);
        } finally {
            coordinator.cleanupInspectionDir(inspectDir);
        }
    }

    @Test
    @Tag("live")
    public void testSpigotDirectIdSearch() {
        ru.milkyway.plugmanreloaded.update.ServerProfile profile = ru.milkyway.plugmanreloaded.update.ServerProfile.of("1.21.1", java.util.Set.of("paper", "spigot", "bukkit"), 21);
        PluginSearch engine = new PluginSearch(null, profile, null);
        List<SearchResultEntry> hits = engine.search("11734", "spigot", 5);
        Assumptions.assumeFalse(hits.isEmpty(), "Spiget API недоступен из этой сети, живой тест поиска по ID пропущен");

        SearchResultEntry entry = hits.get(0);
        assertEquals("spigot", entry.sourceId());
        assertEquals("11734", entry.projectId());
        assertEquals("DeluxeMenus", entry.title());
        assertEquals("https://www.spigotmc.org/resources/11734", entry.url());
    }

    @Test
    public void testCacheHitAndDirectAccess() {
        PluginSearch engine = new PluginSearch(null, null, null);
        SearchResultEntry entry = new SearchResultEntry(
                "modrinth", "test", "TestPlugin", "Author", "1.0",
                "Description", "https://example.com", null,
                100, 10, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
        List<SearchResultEntry> list = List.of(entry);

        engine.putCachedDirect("all:10:testplugin", list);
        assertEquals(1, engine.cacheSize());
        assertSame(list, engine.getCachedDirect("all:10:testplugin"));
        assertNull(engine.getCachedDirect("all:10:nonexistent"));

        List<SearchResultEntry> found = engine.search("TestPlugin", null, 10);
        assertSame(list, found);
    }

    @Test
    public void testCacheKeySeparatesResultLimits() {
        assertNotEquals(PluginSearch.cacheKey("Vault", null, 1), PluginSearch.cacheKey("Vault", null, 10));
        assertEquals(Collections.emptyList(), new PluginSearch(null, null, null).search("Vault", null, 0));
    }

    @Test
    public void testIncompleteSearchIsRetried() throws Exception {
        PluginSearch engine = new PluginSearch(null, null, null);
        AtomicInteger submissions = new AtomicInteger();
        ExecutorService stalled = (ExecutorService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ExecutorService.class}, (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        submissions.incrementAndGet();
                    }
                    return null;
                });
        Field executorField = PluginSearch.class.getDeclaredField("searchExecutor");
        executorField.setAccessible(true);
        executorField.set(engine, stalled);

        try {
            for (int i = 0; i < 2; i++) {
                Thread.currentThread().interrupt();
                try {
                    assertTrue(engine.search("11734", "spigot", 5).isEmpty());
                } finally {
                    Thread.interrupted();
                }
            }
            assertEquals(2, submissions.get());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void testCacheTtlExpiration() {
        TestTicker ticker = new TestTicker();
        PluginSearch engine = new PluginSearch(null, null, null, ticker);
        SearchResultEntry entry = new SearchResultEntry(
                "modrinth", "test", "TestPlugin", "Author", "1.0",
                "Description", "https://example.com", null,
                100, 10, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
        List<SearchResultEntry> list = List.of(entry);

        engine.putCachedDirect("all:testplugin", list);
        assertNotNull(engine.getCachedDirect("all:testplugin"));

        ticker.advance(9, TimeUnit.MINUTES);
        assertNotNull(engine.getCachedDirect("all:testplugin"));

        ticker.advance(2, TimeUnit.MINUTES);
        assertNull(engine.getCachedDirect("all:testplugin"));
        assertEquals(0, engine.cacheSize());
    }

    @Test
    public void testCacheCapacityEviction() {
        PluginSearch engine = new PluginSearch(null, null, null);
        for (int i = 0; i < 250; i++) {
            engine.putCachedDirect("key" + i, Collections.emptyList());
        }
        assertTrue(engine.cacheSize() <= 200);
    }

    @Test
    public void testCacheClear() {
        PluginSearch engine = new PluginSearch(null, null, null);
        for (int i = 0; i < 10; i++) {
            engine.putCachedDirect("key" + i, Collections.emptyList());
        }
        assertEquals(10, engine.cacheSize());
        engine.clearCache();
        assertEquals(0, engine.cacheSize());
    }

    @Test
    public void testCatalogReloadClearsSearchCache() {
        PluginSearch engine = new PluginSearch(null, null, null);
        engine.putCachedDirect("all:testplugin", Collections.emptyList());

        engine.reloadCatalog(null);

        assertEquals(0, engine.cacheSize());
    }

    @Test
    public void testCacheConcurrentAccess() throws Exception {
        PluginSearch engine = new PluginSearch(null, null, null);
        int threads = 8;
        int operationsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "key" + ((threadId * 50 + i) % 300);
                        engine.putCachedDirect(key, Collections.emptyList());
                        engine.getCachedDirect(key);
                        if (i % 50 == 0) {
                            engine.cacheSize();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertTrue(engine.cacheSize() <= 200);
    }

    private static class TestTicker extends com.google.common.base.Ticker {
        private long nanos = 0;

        public void advance(long duration, TimeUnit unit) {
            nanos += unit.toNanos(duration);
        }

        @Override
        public long read() {
            return nanos;
        }
    }
}
