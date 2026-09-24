package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.download.PluginDownloader;
import ru.milkyway.plugmanreloaded.update.install.UpdateInstaller;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempFolderCleanupTest {

    @Test
    void testUpdateInstallerCleansUpEmptyRoot(@TempDir Path pluginsDir) throws Exception {
        Path tmpDir = pluginsDir.resolve(UpdateInstaller.TEMP_DIR_NAME);
        Path sessionDir = tmpDir.resolve("up_12345678");
        Path stagedFile = sessionDir.resolve("TestPlugin-1.0.jar");

        Files.createDirectories(sessionDir);
        Files.writeString(stagedFile, "dummy");

        assertTrue(Files.exists(stagedFile));
        assertTrue(Files.exists(sessionDir));
        assertTrue(Files.exists(tmpDir));

        Files.delete(stagedFile);
        UpdateInstaller.cleanUpEmptyParents(stagedFile);

        assertFalse(Files.exists(sessionDir));
        assertFalse(Files.exists(tmpDir));
        assertTrue(Files.exists(pluginsDir));
    }

    @Test
    void testUpdateInstallerRetainsRootWhenNotSiblingEmpty(@TempDir Path pluginsDir) throws Exception {
        Path tmpDir = pluginsDir.resolve(UpdateInstaller.TEMP_DIR_NAME);
        Path sessionDir1 = tmpDir.resolve("up_11111111");
        Path sessionDir2 = tmpDir.resolve("up_22222222");
        Path staged1 = sessionDir1.resolve("PluginA.jar");
        Path staged2 = sessionDir2.resolve("PluginB.jar");

        Files.createDirectories(sessionDir1);
        Files.createDirectories(sessionDir2);
        Files.writeString(staged1, "dummy");
        Files.writeString(staged2, "dummy");

        Files.delete(staged1);
        UpdateInstaller.cleanUpEmptyParents(staged1);

        assertFalse(Files.exists(sessionDir1));
        assertTrue(Files.exists(sessionDir2));
        assertTrue(Files.exists(tmpDir));
    }

    @Test
    void testPluginDownloaderCleansUpEmptyRoot(@TempDir Path pluginsDir) throws Exception {
        Path tmpDir = pluginsDir.resolve(UpdateInstaller.TEMP_DIR_NAME);
        Path sessionDir = tmpDir.resolve("tx_12345678");
        Path stagedFile = sessionDir.resolve("plugin.jar.tmp");

        Files.createDirectories(sessionDir);
        Files.writeString(stagedFile, "dummy");

        assertTrue(Files.exists(stagedFile));
        assertTrue(Files.exists(sessionDir));
        assertTrue(Files.exists(tmpDir));

        PluginDownloader.cleanupQuietly(sessionDir);

        assertFalse(Files.exists(stagedFile));
        assertFalse(Files.exists(sessionDir));
        assertFalse(Files.exists(tmpDir));
        assertTrue(Files.exists(pluginsDir));
    }

    @Test
    void testPluginDownloaderRetainsRootWhenNotSiblingEmpty(@TempDir Path pluginsDir) throws Exception {
        Path tmpDir = pluginsDir.resolve(UpdateInstaller.TEMP_DIR_NAME);
        Path sessionDir1 = tmpDir.resolve("tx_11111111");
        Path sessionDir2 = tmpDir.resolve("tx_22222222");

        Files.createDirectories(sessionDir1);
        Files.createDirectories(sessionDir2);
        Files.writeString(sessionDir1.resolve("a.tmp"), "a");
        Files.writeString(sessionDir2.resolve("b.tmp"), "b");

        PluginDownloader.cleanupQuietly(sessionDir1);

        assertFalse(Files.exists(sessionDir1));
        assertTrue(Files.exists(sessionDir2));
        assertTrue(Files.exists(tmpDir));
    }

    @Test
    void testCleanStaleTempDirectoriesRemovesOlderAndPreservesNewer(@TempDir Path pluginsDir) throws Exception {
        Path tmpDir = pluginsDir.resolve(UpdateInstaller.TEMP_DIR_NAME);
        Path upStale = tmpDir.resolve("up_old");
        Path txStale = tmpDir.resolve("tx_old");
        Path inspectStale = tmpDir.resolve("inspect_old");
        Path upFresh = tmpDir.resolve("up_new");
        Path foreignDir = tmpDir.resolve("other_folder");

        Files.createDirectories(upStale);
        Files.createDirectories(txStale);
        Files.createDirectories(inspectStale);
        Files.createDirectories(upFresh);
        Files.createDirectories(foreignDir);

        Files.writeString(upStale.resolve("stale.jar"), "stale");
        Files.writeString(txStale.resolve("stale.jar"), "stale");
        Files.writeString(inspectStale.resolve("stale.jar"), "stale");
        Files.writeString(upFresh.resolve("fresh.jar"), "fresh");
        Files.writeString(foreignDir.resolve("keep.txt"), "keep");

        long now = System.currentTimeMillis();
        long threshold = now - 50_000L;

        Files.setLastModifiedTime(upStale, FileTime.fromMillis(now - 100_000L));
        Files.setLastModifiedTime(txStale, FileTime.fromMillis(now - 100_000L));
        Files.setLastModifiedTime(inspectStale, FileTime.fromMillis(now - 100_000L));
        Files.setLastModifiedTime(upFresh, FileTime.fromMillis(now));
        Files.setLastModifiedTime(foreignDir, FileTime.fromMillis(now - 100_000L));

        UpdateInstaller.cleanStaleTempDirectories(pluginsDir.toFile(), threshold);

        assertFalse(Files.exists(upStale));
        assertFalse(Files.exists(txStale));
        assertFalse(Files.exists(inspectStale));
        assertTrue(Files.exists(upFresh));
        assertTrue(Files.exists(foreignDir));
        assertTrue(Files.exists(tmpDir));
    }

    @Test
    void testCleanStaleTempDirectoriesDeletesEmptyRootWhenAllStale(@TempDir Path pluginsDir) throws Exception {
        Path tmpDir = pluginsDir.resolve(UpdateInstaller.TEMP_DIR_NAME);
        Path upStale = tmpDir.resolve("up_old");
        Path txStale = tmpDir.resolve("tx_old");

        Files.createDirectories(upStale);
        Files.createDirectories(txStale);

        Files.writeString(upStale.resolve("stale.jar"), "stale");
        Files.writeString(txStale.resolve("stale.jar"), "stale");

        long now = System.currentTimeMillis();
        long threshold = now - 10_000L;

        Files.setLastModifiedTime(upStale, FileTime.fromMillis(now - 60_000L));
        Files.setLastModifiedTime(txStale, FileTime.fromMillis(now - 60_000L));

        UpdateInstaller.cleanStaleTempDirectories(pluginsDir.toFile(), threshold);

        assertFalse(Files.exists(upStale));
        assertFalse(Files.exists(txStale));
        assertFalse(Files.exists(tmpDir));
        assertTrue(Files.exists(pluginsDir));
    }

    @Test
    void testCleanStaleTempDirectoriesSafeOnInvalidInputs() {
        assertDoesNotThrow(() -> UpdateInstaller.cleanStaleTempDirectories(null));
        assertDoesNotThrow(() -> UpdateInstaller.cleanStaleTempDirectories(new File("non_existent_dir_12345")));
    }
}
