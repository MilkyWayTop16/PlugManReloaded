package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

public class DownloadServiceThreadSafetyTest {

    @BeforeAll
    static void installBukkitStub() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        if (field.get(null) != null) {
            return;
        }

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                DownloadServiceThreadSafetyTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("getPlugins".equals(method.getName())) {
                        return new Plugin[0];
                    }
                    if ("getPlugin".equals(method.getName())) {
                        return null;
                    }
                    return null;
                });

        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(
                DownloadServiceThreadSafetyTest.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> null);

        Logger logger = Logger.getLogger("DownloadServiceThreadSafetyTest");

        Server server = (Server) Proxy.newProxyInstance(
                DownloadServiceThreadSafetyTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> pluginManager;
                    case "getConsoleSender" -> console;
                    case "getLogger" -> logger;
                    default -> null;
                });

        field.set(null, server);
    }

    private static File createJar(Path dir, String name, List<String> deps) throws Exception {
        File file = dir.resolve(name + ".jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(file))) {
            jos.putNextEntry(new ZipEntry("plugin.yml"));
            StringBuilder sb = new StringBuilder();
            sb.append("name: ").append(name).append("\nversion: 1.0\nmain: com.example.Test\n");
            if (deps != null && !deps.isEmpty()) {
                sb.append("depend: [").append(String.join(", ", deps)).append("]\n");
            }
            jos.write(sb.toString().getBytes());
            jos.closeEntry();
        }
        return file;
    }

    private static SearchResultEntry createEntry(String title) {
        return new SearchResultEntry(
                "modrinth", title.toLowerCase(Locale.ROOT), title, "Author", "1.0", "Desc",
                "https://modrinth.com", "https://download", 100L, 10, 100.0,
                List.of(), List.of("paper"), List.of(), null, null, title + ".jar", false, true
        );
    }

    private static class FixedSearchEngine extends PluginSearch {
        private final List<SearchResultEntry> entries;

        FixedSearchEngine(List<SearchResultEntry> entries) {
            super(null, null, null);
            this.entries = entries;
        }

        @Override
        public List<SearchResultEntry> search(String query, String preferredSource, int limit) {
            return entries.stream()
                    .filter(e -> e.title().equalsIgnoreCase(query))
                    .toList();
        }
    }

    @Test
    public void testExplicitStagingDirPassedToJarProvider(@TempDir Path tempDir) throws Exception {
        File targetJar = createJar(tempDir, "TargetPlugin", List.of("DepPlugin"));
        SearchResultEntry targetEntry = createEntry("TargetPlugin");
        SearchResultEntry depEntry = createEntry("DepPlugin");
        File depJar = createJar(tempDir, "DepPlugin", List.of());

        Path explicitStagingDir = tempDir.resolve("explicit_stage_dir");
        Files.createDirectories(explicitStagingDir);

        AtomicReference<Path> capturedStagingDir = new AtomicReference<>();
        DependencyResolver resolver = new DependencyResolver(
                null,
                new FixedSearchEngine(List.of(depEntry)),
                null,
                (entry, stagingDir) -> {
                    capturedStagingDir.set(stagingDir);
                    return depJar;
                }
        );

        DependencyTree tree = resolver.resolve(targetJar, targetEntry, false, explicitStagingDir);

        assertNotNull(tree);
        assertEquals(explicitStagingDir, capturedStagingDir.get());
        assertEquals(1, tree.requiredDependencies().size());
        assertEquals("DepPlugin", tree.requiredDependencies().get(0).title());
    }

    @Test
    public void testNestedDependencyResolutionMaintainsExplicitStagingDir(@TempDir Path tempDir) throws Exception {
        File pluginA = createJar(tempDir, "PluginA", List.of("PluginB"));
        File pluginB = createJar(tempDir, "PluginB", List.of("PluginC"));
        File pluginC = createJar(tempDir, "PluginC", List.of());

        SearchResultEntry entryA = createEntry("PluginA");
        SearchResultEntry entryB = createEntry("PluginB");
        SearchResultEntry entryC = createEntry("PluginC");

        Path explicitStagingDir = tempDir.resolve("nested_staging_dir");
        Files.createDirectories(explicitStagingDir);

        List<Path> capturedDirs = new CopyOnWriteArrayList<>();
        Map<String, File> jarMap = Map.of("PluginB", pluginB, "PluginC", pluginC);

        DependencyResolver resolver = new DependencyResolver(
                null,
                new FixedSearchEngine(List.of(entryB, entryC)),
                null,
                (entry, stagingDir) -> {
                    capturedDirs.add(stagingDir);
                    return jarMap.get(entry.title());
                },
                5
        );

        DependencyTree tree = resolver.resolve(pluginA, entryA, false, explicitStagingDir);

        assertNotNull(tree);
        assertFalse(tree.hasCycles());
        assertEquals(2, capturedDirs.size());
        assertEquals(explicitStagingDir, capturedDirs.get(0));
        assertEquals(explicitStagingDir, capturedDirs.get(1));

        List<String> required = tree.requiredDependencies().stream().map(SearchResultEntry::title).toList();
        assertTrue(required.contains("PluginB"));
        assertTrue(required.contains("PluginC"));
    }

    @Test
    public void testConcurrentResolutionsWithDifferentStagingDirs(@TempDir Path tempDir) throws Exception {
        int concurrency = 16;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        Map<String, File> targetJars = new ConcurrentHashMap<>();
        Map<String, File> depJars = new ConcurrentHashMap<>();
        List<SearchResultEntry> allEntries = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            String pName = "ConcurrentPlug_" + i;
            String dName = "ConcurrentDep_" + i;
            File tJar = createJar(tempDir, pName, List.of(dName));
            File dJar = createJar(tempDir, dName, List.of());
            targetJars.put(pName, tJar);
            depJars.put(dName, dJar);
            allEntries.add(createEntry(pName));
            allEntries.add(createEntry(dName));
        }

        ConcurrentHashMap<String, Path> expectedDirs = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Path> actualDirs = new ConcurrentHashMap<>();
        AtomicBoolean mismatchDetected = new AtomicBoolean(false);

        DependencyResolver resolver = new DependencyResolver(
                null,
                new FixedSearchEngine(allEntries),
                null,
                (entry, stagingDir) -> {
                    Path expected = expectedDirs.get(entry.title());
                    actualDirs.put(entry.title(), stagingDir);
                    if (expected != null && !Objects.equals(expected, stagingDir)) {
                        mismatchDetected.set(true);
                    }
                    return depJars.get(entry.title());
                },
                5
        );

        List<Future<DependencyTree>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final int id = i;
            final String pName = "ConcurrentPlug_" + id;
            final String dName = "ConcurrentDep_" + id;
            final Path stageDir = tempDir.resolve("stage_dir_" + id);
            Files.createDirectories(stageDir);
            expectedDirs.put(dName, stageDir);

            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return resolver.resolve(targetJars.get(pName), createEntry(pName), false, stageDir);
            }));
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS));
        startLatch.countDown();

        for (Future<DependencyTree> f : futures) {
            DependencyTree tree = f.get(10, TimeUnit.SECONDS);
            assertNotNull(tree);
            assertEquals(1, tree.requiredDependencies().size());
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertFalse(mismatchDetected.get(), "Thread safety violation: stagingDir was mixed between concurrent resolutions");
        assertEquals(concurrency, actualDirs.size());
        for (Map.Entry<String, Path> entry : expectedDirs.entrySet()) {
            assertEquals(entry.getValue(), actualDirs.get(entry.getKey()));
        }
    }

    @Test
    public void testSequentialSingleThreadReuseHasNoStaleState(@TempDir Path tempDir) throws Exception {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

        File target1 = createJar(tempDir, "SeqA", List.of("SeqDepA"));
        File dep1 = createJar(tempDir, "SeqDepA", List.of());
        File target2 = createJar(tempDir, "SeqB", List.of("SeqDepB"));
        File dep2 = createJar(tempDir, "SeqDepB", List.of());
        File target3 = createJar(tempDir, "SeqC", List.of("SeqDepC"));
        File dep3 = createJar(tempDir, "SeqDepC", List.of());

        List<SearchResultEntry> entries = List.of(
                createEntry("SeqA"), createEntry("SeqDepA"),
                createEntry("SeqB"), createEntry("SeqDepB"),
                createEntry("SeqC"), createEntry("SeqDepC")
        );

        AtomicReference<Path> receivedDir = new AtomicReference<>();
        DependencyResolver resolver = new DependencyResolver(
                null,
                new FixedSearchEngine(entries),
                null,
                (entry, stagingDir) -> {
                    receivedDir.set(stagingDir);
                    if ("SeqDepA".equals(entry.title())) return dep1;
                    if ("SeqDepB".equals(entry.title())) return dep2;
                    return dep3;
                }
        );

        Path stageDir1 = tempDir.resolve("stage_seq_1");
        singleThreadExecutor.submit(() -> {
            resolver.resolve(target1, createEntry("SeqA"), false, stageDir1);
        }).get(5, TimeUnit.SECONDS);
        assertEquals(stageDir1, receivedDir.get());

        singleThreadExecutor.submit(() -> {
            resolver.resolve(target2, createEntry("SeqB"), false, null);
        }).get(5, TimeUnit.SECONDS);
        assertNull(receivedDir.get(), "Stale stagingDir must not be retained on reused worker thread");

        Path stageDir3 = tempDir.resolve("stage_seq_3");
        singleThreadExecutor.submit(() -> {
            resolver.resolve(target3, createEntry("SeqC"), false, stageDir3);
        }).get(5, TimeUnit.SECONDS);
        assertEquals(stageDir3, receivedDir.get());

        singleThreadExecutor.shutdown();
        assertTrue(singleThreadExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDownloadServiceStageForInspectionHandlesNullSafely(@TempDir Path tempDir) throws Exception {
        sun.misc.Unsafe unsafe;
        var theUnsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafeField.setAccessible(true);
        unsafe = (sun.misc.Unsafe) theUnsafeField.get(null);

        DownloadService service = (DownloadService) unsafe.allocateInstance(DownloadService.class);

        Method stageMethod = DownloadService.class.getDeclaredMethod("stageForInspection", SearchResultEntry.class, Path.class);
        stageMethod.setAccessible(true);

        SearchResultEntry dummyEntry = createEntry("Dummy");
        File resultWithNullDir = (File) stageMethod.invoke(service, dummyEntry, null);
        assertNull(resultWithNullDir);
    }
}
