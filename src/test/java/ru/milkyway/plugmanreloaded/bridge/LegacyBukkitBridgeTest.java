package ru.milkyway.plugmanreloaded.bridge;

import ru.milkyway.plugmanreloaded.managers.SanitizerManager;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.BrigadierManager;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyBukkitBridgeTest {

    private PlugManReloaded plugin;
    private BrigadierManager brigadierManager;
    private SanitizerManager sanitizerManager;
    private Server originalServer;

    @BeforeEach
    void setUp() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        ConfigManager configManager = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        Field msgCfg = ConfigManager.class.getDeclaredField("messagesConfig");
        msgCfg.setAccessible(true);
        msgCfg.set(configManager, new org.bukkit.configuration.file.YamlConfiguration());
        Field cfgField = PlugManReloaded.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, configManager);

        brigadierManager = (BrigadierManager) unsafe.allocateInstance(BrigadierManager.class);
        sanitizerManager = (SanitizerManager) unsafe.allocateInstance(SanitizerManager.class);

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        originalServer = (Server) serverField.get(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, originalServer);
    }

    static class TestBridge extends LegacyBukkitBridge {
        int restartCalls = 0;
        int loadCalls = 0;
        File fileToReturn;
        PluginResult nextLoadResult = PluginResult.ofSuccess("load.success", "version", "1.0");

        TestBridge(PlugManReloaded plugin, BrigadierManager brigadierManager, SanitizerManager sanitizerManager) {
            super(plugin, brigadierManager, sanitizerManager);
        }

        @Override
        public File getPluginFile(Plugin targetPlugin) {
            return fileToReturn;
        }

        @Override
        public PluginResult unloadPlugin(Plugin targetPlugin) {
            return PluginResult.ofSuccess("unload.success");
        }

        @Override
        public PluginResult loadPlugin(File file) {
            loadCalls++;
            return nextLoadResult;
        }

        @Override
        public PluginResult restartPlugin(Plugin targetPlugin) {
            restartCalls++;
            return super.restartPlugin(targetPlugin);
        }
    }

    private static File createValidJar(Path tempDir, String name) throws Exception {
        File jarFile = tempDir.resolve(name + ".jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: " + name + "\nversion: 1.0\nmain: ru.milkyway.TestPlugin\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jarFile;
    }

    private void mockServerWithPluginManager(PluginManager pm) throws Exception {
        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("getPluginManager".equals(method.getName())) return pm;
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    private Plugin createMockPlugin(String name, AtomicBoolean enabledState) {
        PluginDescriptionFile desc = new PluginDescriptionFile(name, "1.0", "ru.milkyway." + name);
        return (Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getDescription" -> desc;
                    case "isEnabled" -> enabledState.get();
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );
    }

    @Test
    @DisplayName("Verify normal successful enable")
    void testNormalSuccessfulEnable() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(false);
        Plugin pluginMock = createMockPlugin("TestPlugin", enabled);

        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("enablePlugin".equals(method.getName())) {
                        enabled.set(true);
                        return null;
                    }
                    return null;
                }
        );
        mockServerWithPluginManager(pm);

        TestBridge bridge = new TestBridge(plugin, brigadierManager, sanitizerManager);
        PluginResult result = bridge.enablePlugin(pluginMock);

        assertTrue(result.success());
        assertEquals("enable.success", result.messageKey());
        assertEquals(0, bridge.restartCalls);
    }

    @Test
    @DisplayName("Verify plugin that self-disables during onEnable does not trigger restartPlugin")
    void testPluginSelfDisablesDuringOnEnableDoesNotRestart() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(false);
        Plugin pluginMock = createMockPlugin("SelfDisablingPlugin", enabled);

        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("enablePlugin".equals(method.getName())) {
                        enabled.set(false);
                        return null;
                    }
                    return null;
                }
        );
        mockServerWithPluginManager(pm);

        TestBridge bridge = new TestBridge(plugin, brigadierManager, sanitizerManager);
        PluginResult result = bridge.enablePlugin(pluginMock);

        assertFalse(result.success());
        assertEquals(FailureReason.ENABLE_FAILED, result.reason());
        assertEquals(0, bridge.restartCalls);
    }

    @Test
    @DisplayName("Verify real enable failure throws exception and does not restart")
    void testRealEnableFailureThrowsException() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(false);
        Plugin pluginMock = createMockPlugin("CrashingPlugin", enabled);

        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("enablePlugin".equals(method.getName())) {
                        throw new RuntimeException("Crash in onEnable");
                    }
                    return null;
                }
        );
        mockServerWithPluginManager(pm);

        TestBridge bridge = new TestBridge(plugin, brigadierManager, sanitizerManager);
        PluginResult result = bridge.enablePlugin(pluginMock);

        assertFalse(result.success());
        assertEquals(FailureReason.ENABLE_FAILED, result.reason());
        assertEquals(0, bridge.restartCalls);
    }

    @Test
    @DisplayName("Verify restart of successful plugin")
    void testRestartSuccessfulPlugin(@TempDir Path tempDir) throws Exception {
        File validJar = createValidJar(tempDir, "RestartSuccessPlugin");
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin pluginMock = createMockPlugin("RestartSuccessPlugin", enabled);

        TestBridge bridge = new TestBridge(plugin, brigadierManager, sanitizerManager);
        bridge.fileToReturn = validJar;
        bridge.nextLoadResult = PluginResult.ofSuccess("load.success", "version", "1.0");

        PluginResult result = bridge.restartPlugin(pluginMock);
        assertTrue(result.success());
        assertEquals("restart.success", result.messageKey());
        assertEquals(1, bridge.loadCalls);
    }

    @Test
    @DisplayName("Verify restart after failure with single retry")
    void testRestartAfterFailureWithRetry(@TempDir Path tempDir) throws Exception {
        File validJar = createValidJar(tempDir, "RetryPlugin");
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin pluginMock = createMockPlugin("RetryPlugin", enabled);

        AtomicInteger attempts = new AtomicInteger(0);
        TestBridge bridge = new TestBridge(plugin, brigadierManager, sanitizerManager) {
            @Override
            public PluginResult loadPlugin(File file) {
                loadCalls++;
                if (attempts.incrementAndGet() == 1) {
                    return PluginResult.ofError(FailureReason.LOAD_FAILED, "error", "file lock contention");
                }
                return PluginResult.ofSuccess("load.success", "version", "1.0");
            }
        };
        bridge.fileToReturn = validJar;

        PluginResult result = bridge.restartPlugin(pluginMock);
        assertTrue(result.success());
        assertEquals("restart.success", result.messageKey());
        assertEquals(2, bridge.loadCalls);

        attempts.set(0);
        TestBridge doubleFailBridge = new TestBridge(plugin, brigadierManager, sanitizerManager) {
            @Override
            public PluginResult loadPlugin(File file) {
                loadCalls++;
                attempts.incrementAndGet();
                return PluginResult.ofError(FailureReason.LOAD_FAILED, "error", "permanent load failure");
            }
        };
        doubleFailBridge.fileToReturn = validJar;

        PluginResult failResult = doubleFailBridge.restartPlugin(pluginMock);
        assertFalse(failResult.success());
        assertEquals(FailureReason.RESTART_LEFT_UNLOADED, failResult.reason());
        assertEquals(2, doubleFailBridge.loadCalls);
    }

    @Test
    @DisplayName("Verify repeated enable and reload contract")
    void testRepeatedEnableAndReload() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin pluginMock = createMockPlugin("TogglePlugin", enabled);

        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("enablePlugin".equals(method.getName())) {
                        enabled.set(true);
                        return null;
                    }
                    if ("disablePlugin".equals(method.getName())) {
                        enabled.set(false);
                        return null;
                    }
                    return null;
                }
        );
        mockServerWithPluginManager(pm);

        TestBridge bridge = new TestBridge(plugin, brigadierManager, sanitizerManager);

        PluginResult alreadyEnabled = bridge.enablePlugin(pluginMock);
        assertFalse(alreadyEnabled.success());
        assertEquals(FailureReason.ALREADY_ENABLED, alreadyEnabled.reason());

        PluginResult reloadResult = bridge.reloadPlugin(pluginMock);
        assertTrue(reloadResult.success());
        assertEquals("reload.success", reloadResult.messageKey());
        assertTrue(enabled.get());

        PluginResult disableResult = bridge.disablePlugin(pluginMock);
        assertTrue(disableResult.success());
        assertFalse(enabled.get());

        PluginResult alreadyDisabled = bridge.disablePlugin(pluginMock);
        assertFalse(alreadyDisabled.success());
        assertEquals(FailureReason.ALREADY_DISABLED, alreadyDisabled.reason());
    }

    @Test
    void restartPluginNeverLeavesThePluginSilentlyUnloadedOnLoadFailure(@TempDir Path tempDir) throws Exception {
        File validJar = createValidJar(tempDir, "SamplePlugin");
        Plugin targetPlugin = createMockPlugin("SamplePlugin", new AtomicBoolean(true));

        TestBridge bridge = new TestBridge(plugin, brigadierManager, sanitizerManager);
        bridge.fileToReturn = validJar;
        bridge.nextLoadResult = PluginResult.ofError(FailureReason.LOAD_FAILED, "error", "load failed");

        PluginResult failResult = bridge.restartPlugin(targetPlugin);
        assertEquals(2, bridge.loadCalls);
        assertFalse(failResult.success());
        assertEquals(FailureReason.RESTART_LEFT_UNLOADED, failResult.reason());

        final int[] attempts = {0};
        TestBridge retrySucceedsBridge = new TestBridge(plugin, brigadierManager, sanitizerManager) {
            @Override
            public PluginResult loadPlugin(File file) {
                attempts[0]++;
                if (attempts[0] == 1) {
                    return PluginResult.ofError(FailureReason.LOAD_FAILED, "error", "transient failure");
                }
                return PluginResult.ofSuccess("load.success", "version", "1.0");
            }
        };
        retrySucceedsBridge.fileToReturn = validJar;

        PluginResult okResult = retrySucceedsBridge.restartPlugin(targetPlugin);
        assertEquals(2, attempts[0]);
        assertTrue(okResult.success());

        ClassNode classNode = new ClassNode();
        try (InputStream in = LegacyBukkitBridge.class.getClassLoader().getResourceAsStream(
                LegacyBukkitBridge.class.getName().replace('.', '/') + ".class")) {
            new ClassReader(in).accept(classNode, 0);
        }

        MethodNode restartMethod = null;
        for (MethodNode m : classNode.methods) {
            if ("restartPlugin".equals(m.name) && "(Lorg/bukkit/plugin/Plugin;)Lru/milkyway/plugmanreloaded/api/PluginResult;".equals(m.desc)) {
                restartMethod = m;
                break;
            }
        }

        assertTrue(restartMethod != null);

        int loadPluginCalls = 0;
        boolean referencesRestartLeftUnloaded = false;

        for (int i = 0; i < restartMethod.instructions.size(); i++) {
            AbstractInsnNode insn = restartMethod.instructions.get(i);
            if (insn instanceof MethodInsnNode minsn) {
                if ("loadPlugin".equals(minsn.name) && "(Ljava/io/File;)Lru/milkyway/plugmanreloaded/api/PluginResult;".equals(minsn.desc)) {
                    loadPluginCalls++;
                }
            } else if (insn instanceof FieldInsnNode finsn) {
                if (finsn.getOpcode() == Opcodes.GETSTATIC
                        && "RESTART_LEFT_UNLOADED".equals(finsn.name)
                        && finsn.owner.contains("FailureReason")) {
                    referencesRestartLeftUnloaded = true;
                }
            }
        }

        assertEquals(2, loadPluginCalls);
        assertTrue(referencesRestartLeftUnloaded);
    }
}
