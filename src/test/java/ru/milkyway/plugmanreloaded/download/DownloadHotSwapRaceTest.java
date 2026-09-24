package ru.milkyway.plugmanreloaded.download;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.configs.MainConfig;
import ru.milkyway.plugmanreloaded.managers.HotSwapManager;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DownloadHotSwapRaceTest {

    @Test
    @DisplayName("Verify installNow calls temporarilyIgnore before publishing the JAR")
    void commitAndActivateTellsHotSwapToIgnoreTheFileItIsAboutToWrite() throws Exception {
        ClassReader reader = new ClassReader(DownloadTransaction.class.getName());
        List<String> invokedMethods = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!"installNow".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDescriptor, boolean isInterface) {
                        invokedMethods.add(owner + "." + mName);
                    }
                };
            }
        }, 0);

        int ignoreIdx = -1;
        int moveIdx = -1;
        for (int i = 0; i < invokedMethods.size(); i++) {
            String m = invokedMethods.get(i);
            if (m.contains("temporarilyIgnore") && ignoreIdx < 0) {
                ignoreIdx = i;
            }
            if (m.contains("PluginDownloader.moveReplacing") && moveIdx < 0) {
                moveIdx = i;
            }
        }

        assertTrue(ignoreIdx >= 0, "temporarilyIgnore must be invoked in installNow/commitAndActivate");
        assertTrue(moveIdx >= 0, "moveReplacing must be invoked in installNow/commitAndActivate");
        assertTrue(ignoreIdx < moveIdx, "temporarilyIgnore must be invoked BEFORE moveReplacing");
    }

    @Test
    @DisplayName("Verify installNow registers target file as temporarily ignored in HotSwapManager")
    void verifyFileIsTemporarilyIgnoredDuringInstall(@TempDir Path tempDir) throws Exception {
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

        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        PluginJarIndex jarIndex = new PluginJarIndex(plugin);
        Field jiField = LifecycleManager.class.getDeclaredField("jarIndex");
        jiField.setAccessible(true);
        jiField.set(lm, jarIndex);

        Field lmField = PlugManReloaded.class.getDeclaredField("pluginLifecycleManager");
        lmField.setAccessible(true);
        lmField.set(plugin, lm);

        HotSwapManager hsm = (HotSwapManager) unsafe.allocateInstance(HotSwapManager.class);
        Field ifuField = HotSwapManager.class.getDeclaredField("ignoredFilesUntil");
        ifuField.setAccessible(true);
        ifuField.set(hsm, new java.util.concurrent.ConcurrentHashMap<>());
        Field hsmField = PlugManReloaded.class.getDeclaredField("hotSwapManager");
        hsmField.setAccessible(true);
        hsmField.set(plugin, hsm);

        ConfigManager cm = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        MainConfig mc = (MainConfig) unsafe.allocateInstance(MainConfig.class);
        Field langField = MainConfig.class.getDeclaredField("language");
        langField.setAccessible(true);
        langField.set(mc, "en");
        Field mcField = ConfigManager.class.getDeclaredField("mainConfig");
        mcField.setAccessible(true);
        mcField.set(cm, mc);
        Field cmField = PlugManReloaded.class.getDeclaredField("configManager");
        cmField.setAccessible(true);
        cmField.set(plugin, cm);

        DownloadTransaction transaction = new DownloadTransaction(plugin, (item, target) -> {}, (item, target) -> {}, () -> {});

        Path stagedJar = tempDir.resolve("normal-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(stagedJar.toFile()))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write("name: NormalPlugin\nversion: 1.0\nmain: com.example.Normal\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        PluginDownloader.StagedItem item = new PluginDownloader.StagedItem(
                stagedJar,
                "NormalPlugin",
                "1.0",
                "modrinth",
                "normal",
                "https://modrinth.com/plugin/normal",
                false,
                null
        );

        Method installNowMethod = DownloadTransaction.class.getDeclaredMethod("installNow", PluginDownloader.StagedItem.class);
        installNowMethod.setAccessible(true);

        File targetFile = pluginsDir.resolve("NormalPlugin.jar").toFile();
        assertFalse(hsm.isTemporarilyIgnored(targetFile.getName()));

        try {
            installNowMethod.invoke(transaction, item);
        } catch (Throwable ignored) {
        }

        assertTrue(hsm.isTemporarilyIgnored(targetFile.getName()),
                "Target file must be temporarily ignored in HotSwapManager");
    }
}
