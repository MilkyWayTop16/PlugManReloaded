package ru.milkyway.plugmanreloaded.download;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.download.DownloadModels.DownloadStatus;
import ru.milkyway.plugmanreloaded.download.DownloadModels.SearchResultEntry;
import ru.milkyway.plugmanreloaded.download.DownloadResolver.DownloadResolution;
import ru.milkyway.plugmanreloaded.download.DownloadResolver.ResolvedDownload;
import ru.milkyway.plugmanreloaded.download.PluginDownloader.StageAttempt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class SmartJavaFallbackProofTest {

    private static HttpServer httpServer;
    private static int serverPort;
    private static final ConcurrentHashMap<String, byte[]> servedEndpoints = new ConcurrentHashMap<>();

    @BeforeAll
    static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] data = servedEndpoints.get(path);
            if (data == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
            exchange.close();
        });
        httpServer.start();
        serverPort = httpServer.getAddress().getPort();
    }

    @AfterAll
    static void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private static byte[] createDummyClassBytes(int majorVersion) {
        byte[] bytes = new byte[8];
        bytes[0] = (byte) 0xCA;
        bytes[1] = (byte) 0xFE;
        bytes[2] = (byte) 0xBA;
        bytes[3] = (byte) 0xBE;
        bytes[4] = 0;
        bytes[5] = 0;
        bytes[6] = (byte) ((majorVersion >> 8) & 0xFF);
        bytes[7] = (byte) (majorVersion & 0xFF);
        return bytes;
    }

    private static byte[] createJarBytes(String pluginName, String version, int javaMajorVersion) throws IOException {
        Path tempFile = Files.createTempFile("test_jar_", ".jar");
        try {
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempFile.toFile()))) {
                jos.putNextEntry(new JarEntry("plugin.yml"));
                String yml = "name: " + pluginName + "\nversion: " + version + "\nmain: com.example.Main\n";
                jos.write(yml.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();

                jos.putNextEntry(new JarEntry("com/example/Main.class"));
                jos.write(createDummyClassBytes(javaMajorVersion));
                jos.closeEntry();
            }
            return Files.readAllBytes(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static PlugManReloaded createMockPlugin(Path tempDir) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        File dataFolder = pluginsDir.resolve("PlugManReloaded").toFile();
        dataFolder.mkdirs();

        Field dataFolderField = JavaPlugin.class.getDeclaredField("dataFolder");
        dataFolderField.setAccessible(true);
        dataFolderField.set(plugin, dataFolder);
        return plugin;
    }

    @Test
    @DisplayName("Proof 1: DownloadResolution handles candidates ordering, empty fallback, and primary item")
    void testDownloadResolutionCandidateStructure() {
        DownloadResolution empty = DownloadResolution.ofCandidates(List.of());
        assertNull(empty.info());
        assertTrue(empty.candidates().isEmpty());
        assertEquals("actions.download.details.no-direct-link", empty.failureDetail());

        ResolvedDownload c1 = new ResolvedDownload("http://127.0.0.1/1.jar", "1.jar", "2.0", null, null);
        ResolvedDownload c2 = new ResolvedDownload("http://127.0.0.1/2.jar", "2.jar", "1.0", null, null);

        DownloadResolution multi = DownloadResolution.ofCandidates(List.of(c1, c2));
        assertNotNull(multi.info());
        assertEquals("2.0", multi.info().versionNumber());
        assertEquals(2, multi.candidates().size());
        assertEquals("1.0", multi.candidates().get(1).versionNumber());
        assertNull(multi.failureDetail());
    }

    @Test
    @DisplayName("Proof 2: stageAndValidate skips incompatible Java 25 candidate, selects compatible Java 17 candidate, and cleans up temp files")
    void testStageAndValidateSmartJavaFallback(@TempDir Path tempDir) throws Exception {
        byte[] java25Jar = createJarBytes("TestPlugin", "2.0-java25", 69);
        byte[] java17Jar = createJarBytes("TestPlugin", "1.0-java17", 61);

        servedEndpoints.put("/plugin-v2-java25.jar", java25Jar);
        servedEndpoints.put("/plugin-v1-java17.jar", java17Jar);

        PlugManReloaded mockPlugin = createMockPlugin(tempDir);
        PluginDownloader downloader = new PluginDownloader(mockPlugin, null, "TestAgent");

        ResolvedDownload candidate1 = new ResolvedDownload(
                "http://127.0.0.1:" + serverPort + "/plugin-v2-java25.jar",
                "plugin-v2.jar",
                "2.0-java25",
                null,
                null
        );
        ResolvedDownload candidate2 = new ResolvedDownload(
                "http://127.0.0.1:" + serverPort + "/plugin-v1-java17.jar",
                "plugin-v1.jar",
                "1.0-java17",
                null,
                null
        );

        DownloadResolver mockResolver = new DownloadResolver(mockPlugin, null) {
            @Override
            DownloadResolution resolve(SearchResultEntry entry) {
                return DownloadResolution.ofCandidates(List.of(candidate1, candidate2));
            }
        };

        Field resolverField = PluginDownloader.class.getDeclaredField("downloadResolver");
        resolverField.setAccessible(true);
        resolverField.set(downloader, mockResolver);

        Path stagingDir = tempDir.resolve("staging");
        Files.createDirectories(stagingDir);

        SearchResultEntry searchEntry = new SearchResultEntry(
                "modrinth",
                "test-plugin",
                "TestPlugin",
                "Author",
                "2.0-java25",
                "Description",
                "http://example.com",
                null, 100, 5, 1.0,
                List.of(), List.of(), List.of(), null, null, null, false, true
        );

        Method stageMethod = PluginDownloader.class.getDeclaredMethod("stageAndValidate", SearchResultEntry.class, Path.class);
        stageMethod.setAccessible(true);
        StageAttempt attempt = (StageAttempt) stageMethod.invoke(downloader, searchEntry, stagingDir);

        assertNotNull(attempt);
        assertNull(attempt.failure(), "Attempt should have succeeded via fallback!");
        assertNotNull(attempt.item(), "StagedItem must not be null");

        assertEquals("TestPlugin", attempt.item().declaredName());
        assertEquals("1.0-java17", attempt.item().version(), "Fallback must have picked version 1.0-java17!");

        try (Stream<Path> files = Files.list(stagingDir)) {
            List<Path> stagedFiles = files.toList();
            assertEquals(1, stagedFiles.size(), "Only exactly ONE staged file must remain in staging directory!");
            assertTrue(stagedFiles.get(0).getFileName().toString().contains("plugin-v1.jar"),
                    "Remaining file must be the compatible v1 jar, v2 must have been deleted");
        }
    }

    @Test
    @DisplayName("Proof 3: If all candidates fail due to incompatible Java, all temp files are deleted and failure reported")
    void testStageAndValidateAllJavaIncompatible(@TempDir Path tempDir) throws Exception {
        byte[] java25Jar1 = createJarBytes("IncompatPlugin", "3.0", 69);
        byte[] java25Jar2 = createJarBytes("IncompatPlugin", "2.0", 69);

        servedEndpoints.put("/incompat-v3.jar", java25Jar1);
        servedEndpoints.put("/incompat-v2.jar", java25Jar2);

        PlugManReloaded mockPlugin = createMockPlugin(tempDir);
        PluginDownloader downloader = new PluginDownloader(mockPlugin, null, "TestAgent");

        ResolvedDownload c1 = new ResolvedDownload("http://127.0.0.1:" + serverPort + "/incompat-v3.jar", "v3.jar", "3.0", null, null);
        ResolvedDownload c2 = new ResolvedDownload("http://127.0.0.1:" + serverPort + "/incompat-v2.jar", "v2.jar", "2.0", null, null);

        DownloadResolver mockResolver = new DownloadResolver(mockPlugin, null) {
            @Override
            DownloadResolution resolve(SearchResultEntry entry) {
                return DownloadResolution.ofCandidates(List.of(c1, c2));
            }
        };

        Field resolverField = PluginDownloader.class.getDeclaredField("downloadResolver");
        resolverField.setAccessible(true);
        resolverField.set(downloader, mockResolver);

        Path stagingDir = tempDir.resolve("staging-fail");
        Files.createDirectories(stagingDir);

        SearchResultEntry searchEntry = new SearchResultEntry(
                "modrinth", "incompat-plugin", "IncompatPlugin", "Author", "3.0", "", "http://example.com",
                null, 10, 0, 1.0, List.of(), List.of(), List.of(), null, null, null, false, true
        );

        Method stageMethod = PluginDownloader.class.getDeclaredMethod("stageAndValidate", SearchResultEntry.class, Path.class);
        stageMethod.setAccessible(true);
        StageAttempt attempt = (StageAttempt) stageMethod.invoke(downloader, searchEntry, stagingDir);

        assertNotNull(attempt);
        assertNull(attempt.item(), "Should not have staged an item");
        assertNotNull(attempt.failure(), "Failure must be present");
        assertEquals(DownloadStatus.INCOMPATIBLE_JAVA, attempt.failure().outcome());

        try (Stream<Path> files = Files.list(stagingDir)) {
            assertEquals(0, files.count(), "All incompatible temp files must be completely wiped from disk!");
        }
    }

    @Test
    @DisplayName("Proof 4: Incompatible Java is prioritized over legacy candidate corruption if all fail")
    void testStageAndValidatePrioritizesJavaIncompatibleWhenLegacyCandidatesCorrupt(@TempDir Path tempDir) throws Exception {
        byte[] java25Jar = createJarBytes("PrioritizePlugin", "2.0", 69);
        byte[] corruptJar = "not a valid jar content".getBytes(StandardCharsets.UTF_8);

        servedEndpoints.put("/prio-java25.jar", java25Jar);
        servedEndpoints.put("/prio-corrupt.jar", corruptJar);

        PlugManReloaded mockPlugin = createMockPlugin(tempDir);
        PluginDownloader downloader = new PluginDownloader(mockPlugin, null, "TestAgent");

        ResolvedDownload c1 = new ResolvedDownload("http://127.0.0.1:" + serverPort + "/prio-java25.jar", "v2.jar", "2.0", null, null);
        ResolvedDownload c2 = new ResolvedDownload("http://127.0.0.1:" + serverPort + "/prio-corrupt.jar", "v1.jar", "1.0", null, null);

        DownloadResolver mockResolver = new DownloadResolver(mockPlugin, null) {
            @Override
            DownloadResolution resolve(SearchResultEntry entry) {
                return DownloadResolution.ofCandidates(List.of(c1, c2));
            }
        };

        Field resolverField = PluginDownloader.class.getDeclaredField("downloadResolver");
        resolverField.setAccessible(true);
        resolverField.set(downloader, mockResolver);

        Path stagingDir = tempDir.resolve("staging-prio");
        Files.createDirectories(stagingDir);

        SearchResultEntry searchEntry = new SearchResultEntry(
                "modrinth", "prio-plugin", "PrioritizePlugin", "Author", "2.0", "", "http://example.com",
                null, 10, 0, 1.0, List.of(), List.of(), List.of(), null, null, null, false, true
        );

        Method stageMethod = PluginDownloader.class.getDeclaredMethod("stageAndValidate", SearchResultEntry.class, Path.class);
        stageMethod.setAccessible(true);
        StageAttempt attempt = (StageAttempt) stageMethod.invoke(downloader, searchEntry, stagingDir);

        assertNotNull(attempt);
        assertNull(attempt.item());
        assertNotNull(attempt.failure());
        assertEquals(DownloadStatus.INCOMPATIBLE_JAVA, attempt.failure().outcome(),
                "Must prioritize INCOMPATIBLE_JAVA so the admin sees the real reason instead of legacy corruption");
        try (Stream<Path> files = Files.list(stagingDir)) {
            assertEquals(0, files.count(), "All temporary files must be cleaned up");
        }
    }
}
