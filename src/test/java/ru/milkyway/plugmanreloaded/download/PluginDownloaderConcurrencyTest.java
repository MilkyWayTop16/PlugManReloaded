package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PluginDownloaderConcurrencyTest {

    @Test
    @DisplayName("Verify that ThreadLocal lastStageFailure has been eliminated completely")
    void noThreadLocalFieldsExistInPluginDownloader() {
        for (Field f : PluginDownloader.class.getDeclaredFields()) {
            assertNotEquals("lastStageFailure", f.getName(),
                    "Поле lastStageFailure должно быть полностью удалено, ошибки должны возвращаться через StageAttempt");
            assertFalse(ThreadLocal.class.isAssignableFrom(f.getType()),
                    "Класс PluginDownloader не должен содержать ThreadLocal полей: " + f.getName());
        }
    }

    @Test
    @DisplayName("Verify concurrent execution across threads produces isolated StageAttempt instances without shared state")
    void concurrentStageForInspectionIsThreadSafeAndIsolated() throws Exception {
        PluginDownloader downloader = new PluginDownloader(null, null, "test-agent");
        Path tempDirA = Files.createTempDirectory("dl_test_a");
        Path tempDirB = Files.createTempDirectory("dl_test_b");

        SearchResultEntry entryA = new SearchResultEntry(
                "modrinth", "test-plugin-a", "test-plugin-a", "Plugin A", "1.0", "",
                "", null, 0, 0, 100.0,
                Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        SearchResultEntry entryB = new SearchResultEntry(
                "github", "test-repo/missing-b", "missing-b", "Plugin B", "1.0", "",
                "", null, 0, 0, 100.0,
                Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<PluginDownloader.StageAttempt> resultA = new AtomicReference<>();
        AtomicReference<PluginDownloader.StageAttempt> resultB = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> futureA = pool.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    resultA.set(downloader.stageForInspection(entryA, tempDirA));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Future<?> futureB = pool.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    resultB.set(downloader.stageForInspection(entryB, tempDirB));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            startGate.countDown();
            futureA.get(10, TimeUnit.SECONDS);
            futureB.get(10, TimeUnit.SECONDS);

            assertNotNull(resultA.get());
            assertNotNull(resultB.get());
            assertNotSame(resultA.get(), resultB.get());

            assertNull(resultA.get().item());
            assertNull(resultA.get().file());
            assertNotNull(resultA.get().failure());
            assertEquals(DownloadStatus.DOWNLOAD_FAILED, resultA.get().failure().outcome());

            assertNull(resultB.get().item());
            assertNull(resultB.get().file());
            assertNotNull(resultB.get().failure());
            assertEquals(DownloadStatus.DOWNLOAD_FAILED, resultB.get().failure().outcome());
        } finally {
            pool.shutdownNow();
            downloader.cleanupInspectionDir(tempDirA);
            downloader.cleanupInspectionDir(tempDirB);
        }
    }

    @Test
    @DisplayName("Verify sequential execution on the same thread never leaks stale state")
    void sequentialExecutionOnSameThreadNeverLeaksStaleErrors() throws Exception {
        PluginDownloader downloader = new PluginDownloader(null, null, "test-agent");
        Path tempDir = Files.createTempDirectory("dl_test_seq");

        try {
            SearchResultEntry nullUrlEntry = new SearchResultEntry(
                    "custom", "dummy", "dummy", "Dummy", "1.0", "",
                    "", null, 0, 0, 100.0,
                    Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                    null, null, null, false, true
            );

            PluginDownloader.StageAttempt first = downloader.stageForInspection(nullUrlEntry, tempDir);
            assertNotNull(first);
            assertNull(first.item());
            assertNotNull(first.failure());
            assertEquals("actions.download.details.no-direct-link", first.failure().detail());

            PluginDownloader.StageAttempt nullEntryAttempt = downloader.stageForInspection(null, tempDir);
            assertNotNull(nullEntryAttempt);
            assertNull(nullEntryAttempt.item());
            assertNotNull(nullEntryAttempt.failure());
            assertEquals("actions.download.details.stage-failed", nullEntryAttempt.failure().detail());

            assertNotEquals(first.failure().detail(), nullEntryAttempt.failure().detail());
        } finally {
            downloader.cleanupInspectionDir(tempDir);
        }
    }

    @Test
    @DisplayName("Verify stageForInspection direct error reporting for dependency resolution")
    void stageForInspectionDirectErrorReporting() throws Exception {
        PluginDownloader downloader = new PluginDownloader(null, null, "test-agent");
        Path inspectDir = Files.createTempDirectory("dl_test_err");

        try {
            PluginDownloader.StageAttempt nullDirAttempt = downloader.stageForInspection(null, null);
            assertNotNull(nullDirAttempt);
            assertFalse(nullDirAttempt.item() != null);
            assertNull(nullDirAttempt.file());
            assertNotNull(nullDirAttempt.failure());
            assertEquals(DownloadStatus.DOWNLOAD_FAILED, nullDirAttempt.failure().outcome());

            File stagedFile = downloader.stageForInspectionFile(null, null);
            assertNull(stagedFile);
        } finally {
            downloader.cleanupInspectionDir(inspectDir);
        }
    }
}
