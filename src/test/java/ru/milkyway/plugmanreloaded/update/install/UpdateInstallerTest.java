package ru.milkyway.plugmanreloaded.update.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
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

    @Test
    void stagesPaperPluginForRestartWithoutFailure(@TempDir Path tempDir) throws Exception {
        ru.milkyway.plugmanreloaded.BukkitServerMock.ensureInitialized();
        sun.misc.Unsafe unsafe;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            return;
        }

        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        File dataFolder = pluginsDir.resolve("PlugManReloaded").toFile();
        dataFolder.mkdirs();

        java.lang.reflect.Field dataFolderField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
        dataFolderField.setAccessible(true);
        dataFolderField.set(plugin, dataFolder);

        ru.milkyway.plugmanreloaded.managers.LifecycleManager lm = (ru.milkyway.plugmanreloaded.managers.LifecycleManager) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.managers.LifecycleManager.class);
        java.lang.reflect.Field lmField = PlugManReloaded.class.getDeclaredField("pluginLifecycleManager");
        lmField.setAccessible(true);
        lmField.set(plugin, lm);

        ru.milkyway.plugmanreloaded.managers.SafetyManager sm = (ru.milkyway.plugmanreloaded.managers.SafetyManager) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.managers.SafetyManager.class);
        java.lang.reflect.Field smField = ru.milkyway.plugmanreloaded.managers.LifecycleManager.class.getDeclaredField("safetyManager");
        smField.setAccessible(true);
        smField.set(lm, sm);

        ru.milkyway.plugmanreloaded.managers.ConfigManager cm = (ru.milkyway.plugmanreloaded.managers.ConfigManager) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.managers.ConfigManager.class);
        java.lang.reflect.Field cmField = PlugManReloaded.class.getDeclaredField("configManager");
        cmField.setAccessible(true);
        cmField.set(plugin, cm);

        ru.milkyway.plugmanreloaded.configs.MainConfig mc = (ru.milkyway.plugmanreloaded.configs.MainConfig) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.configs.MainConfig.class);
        java.lang.reflect.Field uField = ru.milkyway.plugmanreloaded.configs.MainConfig.class.getDeclaredField("unsafeToUnload");
        uField.setAccessible(true);
        uField.set(mc, java.util.Collections.emptySet());
        java.lang.reflect.Field mcField = ru.milkyway.plugmanreloaded.managers.ConfigManager.class.getDeclaredField("mainConfig");
        mcField.setAccessible(true);
        mcField.set(cm, mc);

        UpdateInstaller installer = (UpdateInstaller) unsafe.allocateInstance(UpdateInstaller.class);
        java.lang.reflect.Field pField = UpdateInstaller.class.getDeclaredField("plugin");
        pField.setAccessible(true);
        pField.set(installer, plugin);
        java.lang.reflect.Field bField = UpdateInstaller.class.getDeclaredField("backups");
        bField.setAccessible(true);
        bField.set(installer, new BackupStore(pluginsDir.toFile(), 10));

        Path stagedJar = tempDir.resolve("staged-paper-plugin.jar");
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(stagedJar.toFile()))) {
            jos.putNextEntry(new java.util.jar.JarEntry("paper-plugin.yml"));
            jos.write("name: DoubleDoors\nversion: 1.4.9\nmain: com.example.Main\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        PluginIdentity id = identity("DoubleDoors", "1.2.0", pluginsDir.resolve("DoubleDoors-1.2.0.jar").toFile());
        RemoteVersion ver = version("1.4.9");

        java.lang.reflect.Method swapMethod = UpdateInstaller.class.getDeclaredMethod("swap",
                PluginIdentity.class, RemoteVersion.class, Path.class, Path.class, Path.class,
                boolean.class, List.class, String.class, long.class, boolean.class, boolean.class);
        swapMethod.setAccessible(true);

        InstallResult result = (InstallResult) swapMethod.invoke(installer, id, ver, stagedJar, null, null, false, List.of(), "sha256", 1024L, false, true);

        assertNotNull(result);
        assertEquals(InstallStatus.PENDING_RESTART, result.outcome());
        Path updateJar = pluginsDir.resolve("update").resolve("DoubleDoors-1.2.0.jar");
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(updateJar), "Paper plugin update must be placed into plugins/update/");
    }
}
