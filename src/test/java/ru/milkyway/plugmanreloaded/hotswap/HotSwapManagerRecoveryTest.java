package ru.milkyway.plugmanreloaded.hotswap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.managers.HotSwapManager;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotSwapManagerRecoveryTest {

    @Test
    void isolateFailedUploadMovesBadJarAsideWithFailedSuffix(@TempDir Path tempDir) throws Exception {
        File newFile = tempDir.resolve("Essentials.jar").toFile();
        Files.writeString(newFile.toPath(), "broken jar bytes", StandardCharsets.UTF_8);
        File oldFile = tempDir.resolve("Essentials.jar.old").toFile();
        Files.writeString(oldFile.toPath(), "old working jar bytes", StandardCharsets.UTF_8);

        Method isolate = HotSwapManager.class.getDeclaredMethod("isolateFailedUpload", File.class, File.class);
        isolate.setAccessible(true);
        isolate.invoke(null, newFile, oldFile);

        File failedFile = tempDir.resolve("Essentials.jar.failed").toFile();
        assertTrue(failedFile.exists(),
                "новый файл, который не загрузился, обязан быть перемещён в .failed, а не остаться под именем "
                        + "рабочего плагина — иначе следующий цикл HotSwap увидит его как ENTRY_CREATE и повторит "
                        + "ту же неудачную попытку загрузки по кругу");
        assertFalse(newFile.exists(), "исходный файл на старом месте не должен остаться — он перемещён в .failed");
        assertTrue(oldFile.exists(), "резервная копия .old не должна пострадать при изоляции сбойного файла");
    }

    @Test
    void isolateFailedUploadDoesNothingWhenFileIsAlreadyTheOldBackup(@TempDir Path tempDir) throws Exception {
        File sameFile = tempDir.resolve("Essentials.jar").toFile();
        Files.writeString(sameFile.toPath(), "content", StandardCharsets.UTF_8);

        Method isolate = HotSwapManager.class.getDeclaredMethod("isolateFailedUpload", File.class, File.class);
        isolate.setAccessible(true);
        isolate.invoke(null, sameFile, sameFile);

        assertTrue(sameFile.exists(),
                "если file.equals(oldFile) (перезапись собственного бэкапа), метод не должен трогать файл вообще");
        File failedFile = tempDir.resolve("Essentials.jar.failed").toFile();
        assertFalse(failedFile.exists(), ".failed не должен создаваться, когда file и oldFile — один и тот же файл");
    }

    @Test
    void backupOrRenameOldFileMovesSourceOntoDestination(@TempDir Path tempDir) throws Exception {
        File src = tempDir.resolve("Essentials.jar").toFile();
        Files.writeString(src.toPath(), "working plugin bytes", StandardCharsets.UTF_8);
        File dst = tempDir.resolve("Essentials.jar.old").toFile();

        Method backup = HotSwapManager.class.getDeclaredMethod(
                "backupOrRenameOldFile", File.class, File.class, int.class, long.class);
        backup.setAccessible(true);
        boolean result = (boolean) backup.invoke(null, src, dst, 3, 5L);

        assertTrue(result, "перенос старого рабочего jar в .old обязан завершиться успешно на обычной файловой системе");
        assertTrue(dst.exists(), ".old файл обязан появиться");
        assertFalse(src.exists(), "исходный файл обязан исчезнуть после переноса в .old");
        assertEquals("working plugin bytes", Files.readString(dst.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void handleFileDeletedGuardsAgainstAtomicMoveFalsePositive(@TempDir Path tempDir) throws Exception {
        ru.milkyway.plugmanreloaded.BukkitServerMock.ensureInitialized();
        File pluginsDir = tempDir.toFile();
        File pluginJar = new File(pluginsDir, "ExistingPlugin.jar");
        Files.writeString(pluginJar.toPath(), "jar bytes", StandardCharsets.UTF_8);

        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);

        ru.milkyway.plugmanreloaded.PlugManReloaded plugin = (ru.milkyway.plugmanreloaded.PlugManReloaded) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.PlugManReloaded.class);
        ru.milkyway.plugmanreloaded.managers.ConfigManager configManager = (ru.milkyway.plugmanreloaded.managers.ConfigManager) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.managers.ConfigManager.class);
        ru.milkyway.plugmanreloaded.configs.MainConfig mainConfig = (ru.milkyway.plugmanreloaded.configs.MainConfig) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.configs.MainConfig.class);

        java.lang.reflect.Field mainConfigField = ru.milkyway.plugmanreloaded.managers.ConfigManager.class.getDeclaredField("mainConfig");
        mainConfigField.setAccessible(true);
        mainConfigField.set(configManager, mainConfig);

        java.lang.reflect.Field autoUnloadField = ru.milkyway.plugmanreloaded.configs.MainConfig.class.getDeclaredField("hotSwapAutoUnloadOnDelete");
        autoUnloadField.setAccessible(true);
        autoUnloadField.setBoolean(mainConfig, true);

        java.lang.reflect.Field cmField = ru.milkyway.plugmanreloaded.PlugManReloaded.class.getDeclaredField("configManager");
        cmField.setAccessible(true);
        cmField.set(plugin, configManager);

        File dataFolder = new File(pluginsDir, "PlugManReloaded");
        java.lang.reflect.Field dfField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
        dfField.setAccessible(true);
        dfField.set(plugin, dataFolder);

        ru.milkyway.plugmanreloaded.managers.LifecycleManager lifecycleManager = (ru.milkyway.plugmanreloaded.managers.LifecycleManager) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.managers.LifecycleManager.class);

        HotSwapManager manager = (HotSwapManager) unsafe.allocateInstance(HotSwapManager.class);
        java.lang.reflect.Field pField = HotSwapManager.class.getDeclaredField("plugin");
        pField.setAccessible(true);
        pField.set(manager, plugin);

        java.lang.reflect.Field lmField = HotSwapManager.class.getDeclaredField("lifecycleManager");
        lmField.setAccessible(true);
        lmField.set(manager, lifecycleManager);

        java.lang.reflect.Field runningField = HotSwapManager.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.setBoolean(manager, true);

        java.lang.reflect.Field lastModField = HotSwapManager.class.getDeclaredField("lastModifiedDebounce");
        lastModField.setAccessible(true);
        lastModField.set(manager, new java.util.concurrent.ConcurrentHashMap<>());

        java.lang.reflect.Field ignoredField = HotSwapManager.class.getDeclaredField("ignoredFilesUntil");
        ignoredField.setAccessible(true);
        ignoredField.set(manager, new java.util.concurrent.ConcurrentHashMap<>());

        Method handleFileDeleted = HotSwapManager.class.getDeclaredMethod("handleFileDeleted", String.class);
        handleFileDeleted.setAccessible(true);
        handleFileDeleted.invoke(manager, "ExistingPlugin.jar");

        assertTrue(pluginJar.exists());
    }
}
