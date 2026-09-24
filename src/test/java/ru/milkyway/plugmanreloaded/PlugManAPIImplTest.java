package ru.milkyway.plugmanreloaded;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PlugManAPI;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.api.impl.PlugManAPIImpl;
import ru.milkyway.plugmanreloaded.bridge.PlatformBridge;
import ru.milkyway.plugmanreloaded.configs.MainConfig;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PlugManAPIImplTest {

    @BeforeAll
    static void initBukkit() {
        BukkitServerMock.ensureInitialized();
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static void setField(Object target, Class<?> clz, String name, Object value) throws Exception {
        Field f = clz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        setField(target, target.getClass(), name, value);
    }

    private static Path createPluginJar(Path path, String name, String version) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            String yaml = "name: " + name + "\nversion: '" + version + "'\nmain: org.example.Test\n";
            zos.write(yaml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return path;
    }

    private static Plugin createMockPlugin(String name, String version) throws Exception {
        String yaml = "name: " + name + "\nversion: '" + version + "'\nmain: org.example.Test\n";
        PluginDescriptionFile desc = new PluginDescriptionFile(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return (Plugin) Proxy.newProxyInstance(
                PlugManAPIImplTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> desc.getName();
                    case "getDescription" -> desc;
                    case "isEnabled" -> true;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> desc.getName().hashCode();
                    case "toString" -> "Plugin[" + desc.getName() + "]";
                    default -> null;
                }
        );
    }

    @Test
    void deletePluginByInstanceBacksUpBeforeDeletingTheJar(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path dataDir = pluginsDir.resolve("PlugManReloaded");
        Files.createDirectories(dataDir);
        Path jarPath = createPluginJar(pluginsDir.resolve("ActivePlugin.jar"), "ActivePlugin", "1.0.0");
        long originalSize = Files.size(jarPath);

        sun.misc.Unsafe unsafe = getUnsafe();
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        setField(plugin, JavaPlugin.class, "dataFolder", dataDir.toFile());

        ConfigManager cm = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        MainConfig mc = (MainConfig) unsafe.allocateInstance(MainConfig.class);
        setField(mc, "backupKeepDays", 7);
        setField(mc, "backupMaxPerPlugin", 5);
        setField(cm, "mainConfig", mc);
        setField(plugin, "configManager", cm);

        Plugin targetPlugin = createMockPlugin("ActivePlugin", "1.0.0");

        PlatformBridge bridge = (PlatformBridge) Proxy.newProxyInstance(
                PlugManAPIImplTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unloadPlugin" -> PluginResult.ofSuccess("unload.success");
                    case "getPluginFile" -> jarPath.toFile();
                    default -> null;
                }
        );

        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        setField(lm, "bridge", bridge);
        Map<Plugin, File> cache = new WeakHashMap<>();
        cache.put(targetPlugin, jarPath.toFile());
        setField(lm, "pluginFileCache", cache);

        PluginJarIndex jarIndex = (PluginJarIndex) unsafe.allocateInstance(PluginJarIndex.class);
        setField(jarIndex, "snapshot", new AtomicReference<>(PluginJarIndex.IndexSnapshot.empty()));
        setField(jarIndex, "warnedDuplicates", ConcurrentHashMap.newKeySet());

        PlugManAPIImpl api = (PlugManAPIImpl) unsafe.allocateInstance(PlugManAPIImpl.class);
        setField(api, "plugin", plugin);
        setField(api, "lifecycleManager", lm);
        setField(api, "jarIndex", jarIndex);

        PluginResult result = api.deletePlugin(targetPlugin);

        assertTrue(result.success());
        assertTrue(Files.notExists(jarPath));

        Path backupDir = pluginsDir.resolve(".plugmanreloaded-backups");
        assertTrue(Files.isDirectory(backupDir));
        try (Stream<Path> list = Files.list(backupDir)) {
            List<Path> backups = list.filter(p -> p.getFileName().toString().startsWith("ActivePlugin-1.0.0-")).toList();
            assertEquals(1, backups.size());
            assertEquals(originalSize, Files.size(backups.get(0)));
        }
    }

    @Test
    void deletePluginByNameBacksUpBeforeDeletingTheJar(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path dataDir = pluginsDir.resolve("PlugManReloaded");
        Files.createDirectories(dataDir);
        Path jarPath = createPluginJar(pluginsDir.resolve("UnloadedPlugin.jar"), "UnloadedPlugin", "2.1.0");
        long originalSize = Files.size(jarPath);

        sun.misc.Unsafe unsafe = getUnsafe();
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        setField(plugin, JavaPlugin.class, "dataFolder", dataDir.toFile());

        ConfigManager cm = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        MainConfig mc = (MainConfig) unsafe.allocateInstance(MainConfig.class);
        setField(mc, "backupKeepDays", 7);
        setField(mc, "backupMaxPerPlugin", 5);
        setField(cm, "mainConfig", mc);
        setField(plugin, "configManager", cm);

        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        setField(lm, "pluginFileCache", new WeakHashMap<>());

        PluginJarIndex jarIndex = (PluginJarIndex) unsafe.allocateInstance(PluginJarIndex.class);
        setField(lm, "jarIndex", jarIndex);
        PluginJarIndex.JarInfo info = new PluginJarIndex.JarInfo(jarPath.toFile(), "UnloadedPlugin", "2.1.0", "Author");
        PluginJarIndex.IndexSnapshot snap = new PluginJarIndex.IndexSnapshot(
                List.of(info),
                Map.of("unloadedplugin", info),
                Collections.emptyMap(),
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                1
        );
        setField(jarIndex, "snapshot", new AtomicReference<>(snap));
        setField(jarIndex, "warnedDuplicates", ConcurrentHashMap.newKeySet());

        PlugManAPIImpl api = (PlugManAPIImpl) unsafe.allocateInstance(PlugManAPIImpl.class);
        setField(api, "plugin", plugin);
        setField(api, "lifecycleManager", lm);
        setField(api, "jarIndex", jarIndex);

        PluginResult result = api.deletePlugin("UnloadedPlugin");

        assertTrue(result.success());
        assertTrue(Files.notExists(jarPath));

        Path backupDir = pluginsDir.resolve(".plugmanreloaded-backups");
        assertTrue(Files.isDirectory(backupDir));
        try (Stream<Path> list = Files.list(backupDir)) {
            List<Path> backups = list.filter(p -> p.getFileName().toString().startsWith("UnloadedPlugin-2.1.0-")).toList();
            assertEquals(1, backups.size());
            assertEquals(originalSize, Files.size(backups.get(0)));
        }
    }

    @Test
    void deleteProtectedPluginByNameReturnsErrorAndPreservesJar(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path jarPath = createPluginJar(pluginsDir.resolve("PlugManReloaded.jar"), "PlugManReloaded", "1.1-beta");

        sun.misc.Unsafe unsafe = getUnsafe();
        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        setField(lm, "plugin", plugin);

        PlugManAPIImpl api = (PlugManAPIImpl) unsafe.allocateInstance(PlugManAPIImpl.class);
        setField(api, "lifecycleManager", lm);

        PluginResult result = api.deletePlugin("PlugManReloaded");

        assertFalse(result.success());
        assertEquals(FailureReason.SELF_PROTECTED, result.reason());
        assertTrue(Files.exists(jarPath));
    }

    @Test
    void aliasMethodsWorkViaInterface() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        setField(lm, "plugin", plugin);

        PlugManAPI api = (PlugManAPI) unsafe.allocateInstance(PlugManAPIImpl.class);
        setField(api, "lifecycleManager", lm);

        assertTrue(api.isProtected("PlugManReloaded"));
        assertFalse(api.isLoaded("NonExistentPlugin"));
        assertFalse(api.isEnabled("NonExistentPlugin"));
    }
}
