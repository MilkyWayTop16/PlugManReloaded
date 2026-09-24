package ru.milkyway.plugmanreloaded.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PluginDownloaderTransactionTest {

    @Test
    void restoresReplacedFileWithoutConsumingTheBackup(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("plugins").resolve("Plugin.jar");
        Path transactionDir = tempDir.resolve("transaction");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old artifact");

        Path backup = PluginDownloader.backupForRollback(target, transactionDir);
        Files.writeString(target, "new artifact");

        assertTrue(PluginDownloader.restoreFileChange(target, backup, false));
        assertEquals("old artifact", Files.readString(target));
        assertTrue(Files.exists(backup));
    }

    @Test
    void removesOnlyFileCreatedByTheTransaction(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("plugins").resolve("NewPlugin.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "new artifact");

        assertTrue(PluginDownloader.restoreFileChange(target, null, true));
        assertFalse(Files.exists(target));
    }

    @Test
    void leavesExistingFileUntouchedWhenNoBackupWasCreated(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("plugins").resolve("Existing.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "existing artifact");

        assertFalse(PluginDownloader.restoreFileChange(target, null, false));
        assertEquals("existing artifact", Files.readString(target));
    }
}
