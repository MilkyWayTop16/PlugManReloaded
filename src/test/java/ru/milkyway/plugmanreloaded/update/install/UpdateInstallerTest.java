package ru.milkyway.plugmanreloaded.update.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.update.UpdateModels.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateInstallerTest {

    private static final File PLUGINS_DIR = new File("plugins");

    private static PluginIdentity identity(String name, String version, File jarFile) {
        return new PluginIdentity(name, "x.y.Main", version, List.of("author"), null, null, null, jarFile);
    }

    private static RemoteVersion version(String number) {
        return new RemoteVersion("modrinth", "ref", "https://example.com", number,
                ReleaseChannel.RELEASE, Set.of(), Set.of(), "https://example.com/dl", "file.jar",
                null, null, 1024L, Instant.now());
    }

    @Test
    void keepsThePluginNameWhenTheVersionDigitAlsoAppearsInIt() {
        File oldJar = new File("plugins", "Lib1API-1.jar");
        File result = UpdateInstaller.determineTargetFile(oldJar, version("2"), identity("Lib1API", "1", oldJar), PLUGINS_DIR);

        assertEquals("Lib1API-2.jar", result.getName(),
                "Замена версии в имени файла обязана трогать только версионную часть. "
                        + "String.replace(\"1\", \"2\") на \"Lib1API-1.jar\" даёт испорченное \"Lib2API-2.jar\" — "
                        + "цифра в самом имени плагина, случайно совпавшая со старой версией, тоже заменяется");
    }

    @Test
    void keepsThePluginNameWhenTheVersionIsAShortDigitInsideTheName() {
        File oldJar = new File("plugins", "L2Adena-2.jar");
        File result = UpdateInstaller.determineTargetFile(oldJar, version("3"), identity("L2Adena", "2", oldJar), PLUGINS_DIR);

        assertEquals("L2Adena-3.jar", result.getName(),
                "Версия стоит последним вхождением в имени файла — нужно заменить именно его, "
                        + "а цифру 2 внутри самого имени плагина \"L2Adena\" не трогать вовсе");
    }

    @Test
    void handlesTheOrdinaryDottedVersionCase() {
        File oldJar = new File("plugins", "NormalPlugin-1.7.2.jar");
        File result = UpdateInstaller.determineTargetFile(oldJar, version("1.8.0"), identity("NormalPlugin", "1.7.2", oldJar), PLUGINS_DIR);

        assertEquals("NormalPlugin-1.8.0.jar", result.getName());
    }

    @Test
    void keepsOldFileNameWhenTheVersionIsNotPresentInIt() {
        File oldJar = new File("plugins", "CustomName.jar");
        File result = UpdateInstaller.determineTargetFile(oldJar, version("2.0"), identity("CustomName", "1.0", oldJar), PLUGINS_DIR);

        assertEquals(oldJar, result);
    }

    @Test
    void createsAFreshNameWhenThereIsNoOldFile() {
        File result = UpdateInstaller.determineTargetFile(null, version("1.0"), identity("BrandNew", "0.9", null), PLUGINS_DIR);
        assertEquals("BrandNew.jar", result.getName());
    }

    @Test
    void sanitizesInvalidPathCharactersInNewVersion() {
        File oldJar = new File("plugins", "WebPlugin-1.0.jar");
        File result = UpdateInstaller.determineTargetFile(oldJar, version("release/2.0:build"), identity("WebPlugin", "1.0", oldJar), PLUGINS_DIR);
        assertEquals("WebPlugin-release_2.0_build.jar", result.getName());
    }

    @Test
    void preservesThePreviousPendingArtifactBeforeReplacement(@TempDir Path tempDir) throws Exception {
        File target = tempDir.resolve("update").resolve("Plugin.jar").toFile();
        Path staged = tempDir.resolve("staged.jar");
        Files.createDirectories(target.toPath().getParent());
        Files.writeString(target.toPath(), "previous pending");
        Files.writeString(staged, "next pending");
        BackupStore backups = new BackupStore(tempDir.toFile(), 10);

        Path backup = UpdateInstaller.replacePendingArtifact(
                staged, target, backups, "Plugin", "1.0");

        assertNotNull(backup);
        assertEquals("previous pending", Files.readString(backup));
        assertEquals("next pending", Files.readString(target.toPath()));
    }

    @Test
    void doesNotRequireABackupForANewPendingArtifact(@TempDir Path tempDir) throws Exception {
        File target = tempDir.resolve("update").resolve("Plugin.jar").toFile();
        Path staged = tempDir.resolve("staged.jar");
        Files.writeString(staged, "pending");
        BackupStore backups = new BackupStore(tempDir.toFile(), 10);

        Path backup = UpdateInstaller.replacePendingArtifact(
                staged, target, backups, "Plugin", "1.0");

        assertNull(backup);
        assertEquals("pending", Files.readString(target.toPath()));
    }

    @Test
    void leavesPreviousPendingArtifactUntouchedWhenReplacementCannotStart(@TempDir Path tempDir) throws Exception {
        File target = tempDir.resolve("update").resolve("Plugin.jar").toFile();
        Files.createDirectories(target.toPath().getParent());
        Files.writeString(target.toPath(), "previous pending");
        BackupStore backups = new BackupStore(tempDir.toFile(), 10);

        assertThrows(Exception.class, () -> UpdateInstaller.replacePendingArtifact(
                tempDir.resolve("missing.jar"), target, backups, "Plugin", "1.0"));
        assertEquals("previous pending", Files.readString(target.toPath()));
    }
}
