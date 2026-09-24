package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.update.install.BackupStore;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupStoreTest {

    @Test
    void testBackupPreservedEvenIfSourceJarIsOld(@TempDir Path tempDir) throws Exception {
        File pluginsDir = tempDir.toFile();
        File sourceJar = new File(pluginsDir, "SamplePlugin-1.0.jar");
        Files.writeString(sourceJar.toPath(), "PK_DUMMY_CONTENT");
        Files.setLastModifiedTime(sourceJar.toPath(), FileTime.fromMillis(System.currentTimeMillis() - (100L * 24L * 60L * 60L * 1000L)));

        BackupStore store = new BackupStore(pluginsDir, 1, 3);
        Path backup = store.backup("SamplePlugin", "1.0", sourceJar);

        assertNotNull(backup);
        assertTrue(Files.exists(backup));

        Path backupDir = pluginsDir.toPath().resolve(".plugmanreloaded-backups");
        assertTrue(Files.exists(backupDir));
        try (var s = Files.list(backupDir)) {
            List<Path> files = s.toList();
            assertEquals(1, files.size());
        }
    }

    @Test
    void testMaxPerPluginPruning(@TempDir Path tempDir) throws Exception {
        File pluginsDir = tempDir.toFile();
        File sourceJar = new File(pluginsDir, "SamplePlugin-1.0.jar");
        Files.writeString(sourceJar.toPath(), "PK_DUMMY_CONTENT");

        BackupStore store = new BackupStore(pluginsDir, 30, 2);
        Path b1 = store.backup("SamplePlugin", "1.0", sourceJar);
        Thread.sleep(1100L);
        Path b2 = store.backup("SamplePlugin", "1.1", sourceJar);
        Thread.sleep(1100L);
        Path b3 = store.backup("SamplePlugin", "1.2", sourceJar);

        Path backupDir = pluginsDir.toPath().resolve(".plugmanreloaded-backups");
        try (var s = Files.list(backupDir)) {
            List<Path> files = s.toList();
            assertEquals(2, files.size());
            assertTrue(Files.exists(b2));
            assertTrue(Files.exists(b3));
            assertTrue(!Files.exists(b1));
        }
    }

    @Test
    void testBackupFolderArchive(@TempDir Path tempDir) throws Exception {
        File pluginsDir = tempDir.toFile();
        File dataFolder = new File(pluginsDir, "SamplePlugin");
        dataFolder.mkdirs();
        File subDir = new File(dataFolder, "sub");
        subDir.mkdirs();
        Files.writeString(new File(dataFolder, "config.yml").toPath(), "test: true");
        Files.writeString(new File(subDir, "messages.yml").toPath(), "msg: hi");

        BackupStore store = new BackupStore(pluginsDir, 7, 3);
        Path backupZip = store.backupFolder("SamplePlugin", dataFolder);

        assertNotNull(backupZip);
        assertTrue(Files.exists(backupZip));
        assertTrue(backupZip.getFileName().toString().endsWith(".zip"));

        Path backupDir = pluginsDir.toPath().resolve(".plugmanreloaded-backups");
        try (var s = Files.list(backupDir)) {
            List<Path> files = s.toList();
            assertEquals(1, files.size());
        }

        File restoreTarget = new File(pluginsDir, "RestoredPlugin");
        boolean restored = store.restoreFolder(backupZip, restoreTarget);
        assertTrue(restored);
        assertTrue(new File(restoreTarget, "config.yml").exists());
        assertTrue(new File(restoreTarget, "sub/messages.yml").exists());
        assertEquals("test: true", Files.readString(new File(restoreTarget, "config.yml").toPath()));
        assertEquals("msg: hi", Files.readString(new File(restoreTarget, "sub/messages.yml").toPath()));
    }
}
