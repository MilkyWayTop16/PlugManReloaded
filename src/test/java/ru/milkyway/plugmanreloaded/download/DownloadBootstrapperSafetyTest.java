package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

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
import ru.milkyway.plugmanreloaded.utils.JarValidator;

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

class DownloadBootstrapperSafetyTest {

    @Test
    @DisplayName("Verify stageAndValidate queries hasBootstrapper on PreFlightReport in bytecode")
    void stageAndValidateComputesRequiresRestartFromTheReport() throws Exception {
        ClassReader reader = new ClassReader(PluginDownloader.class.getName());
        List<String> invokedMethods = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!"stageAndValidate".equals(name)) {
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

        assertTrue(invokedMethods.stream().anyMatch(m -> m.contains("hasBootstrapper")),
                "stageAndValidate must query report.hasBootstrapper()");
        assertTrue(invokedMethods.stream().anyMatch(m -> m.contains("isPaperPlugin")),
                "stageAndValidate must query report.isPaperPlugin()");
    }

    @Test
    @DisplayName("Verify commitAndActivate defers bootstrapper plugins to update folder without loading")
    void commitAndActivateDefersBootstrapperPluginsToUpdateFolderInsteadOfHotLoading(@TempDir Path tempDir) throws Exception {
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

        PluginDownloader downloader = new PluginDownloader(plugin, null, "TestAgent");

        Path stagedJar = tempDir.resolve("staged-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(stagedJar.toFile()))) {
            out.putNextEntry(new JarEntry("paper-plugin.yml"));
            out.write("name: BootstrapperPlugin\nversion: 1.0\nmain: com.example.Main\nbootstrapper: com.example.Boot\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        PluginDownloader.StagedItem item = new PluginDownloader.StagedItem(
                stagedJar,
                "BootstrapperPlugin",
                "1.0",
                "hangar",
                "author/bootstrapper",
                "https://hangar.papermc.io/author/bootstrapper",
                true,
                null
        );

        Method commitMethod = PluginDownloader.class.getDeclaredMethod("commitAndActivate", List.class);
        commitMethod.setAccessible(true);
        DownloadResult result = (DownloadResult) commitMethod.invoke(downloader, List.of(item));

        assertNotNull(result);
        assertEquals(DownloadStatus.BOOTSTRAPPER_RESTART_REQUIRED, result.outcome());
        assertEquals("BootstrapperPlugin", result.pluginName());

        Path updateJar = pluginsDir.resolve("update").resolve("BootstrapperPlugin.jar");
        assertTrue(Files.exists(updateJar), "Bootstrapper plugin must be staged to plugins/update/");
        assertFalse(Files.exists(pluginsDir.resolve("BootstrapperPlugin.jar")),
                "Bootstrapper plugin must NOT be installed to root plugins directory");
    }
}
