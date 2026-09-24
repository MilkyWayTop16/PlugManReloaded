package ru.milkyway.plugmanreloaded.bridge;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.BrigadierManager;
import ru.milkyway.plugmanreloaded.managers.SanitizerManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class ModernPaperBridgeTest {

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

    private void mockServerWithSimplePluginManager() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        SimplePluginManager spm = (SimplePluginManager) unsafe.allocateInstance(SimplePluginManager.class);

        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("getPluginManager".equals(method.getName())) return spm;
                    return null;
                }
        );
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    @Test
    @DisplayName("Verify postUnloadCleanup doesn't crash when PaperPluginManagerImpl is absent")
    void testCleanPaperPluginManagerSafeFallback() throws Exception {
        mockServerWithSimplePluginManager();

        ModernPaperBridge bridge = new ModernPaperBridge(plugin, brigadierManager, sanitizerManager);

        Plugin targetPlugin = (Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "MockPlugin";
                    return null;
                }
        );

        java.lang.reflect.Method cleanMethod = ModernPaperBridge.class.getDeclaredMethod("cleanPaperPluginManager", Plugin.class);
        cleanMethod.setAccessible(true);

        cleanMethod.invoke(bridge, targetPlugin);
    }

    @Test
    @DisplayName("Remove Paper lookup aliases owned by the unloaded plugin only")
    void testRemovesLookupNamesByPluginIdentity() throws Exception {
        Plugin oldPlugin = mockPlugin("Provider");
        Plugin otherPlugin = mockPlugin("OtherProvider");
        Map<String, Plugin> lookupNames = new HashMap<>();
        lookupNames.put("provider", oldPlugin);
        lookupNames.put("vault", oldPlugin);
        lookupNames.put("economy", otherPlugin);

        Method removeMethod = ModernPaperBridge.class.getDeclaredMethod("removePluginLookupNames", Map.class, Plugin.class);
        removeMethod.setAccessible(true);
        removeMethod.invoke(null, lookupNames, oldPlugin);

        org.junit.jupiter.api.Assertions.assertFalse(lookupNames.containsKey("provider"));
        org.junit.jupiter.api.Assertions.assertFalse(lookupNames.containsKey("vault"));
        org.junit.jupiter.api.Assertions.assertSame(otherPlugin, lookupNames.get("economy"));
    }

    @Test
    @DisplayName("Enabling a disabled Paper plugin reloads it with a fresh classloader")
    void testEnableReloadsDisabledPlugin() {
        Plugin target = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "SmokeProvider";
                    case "isEnabled" -> false;
                    default -> null;
                });
        AtomicReference<Plugin> restarted = new AtomicReference<>();
        ModernPaperBridge bridge = new ModernPaperBridge(plugin, brigadierManager, sanitizerManager) {
            @Override
            public PluginResult restartPlugin(Plugin targetPlugin) {
                restarted.set(targetPlugin);
                return PluginResult.ofSuccess("restart.success", "plugin", targetPlugin.getName(), "version", "1.0");
            }
        };

        PluginResult result = bridge.enablePlugin(target);

        org.junit.jupiter.api.Assertions.assertSame(target, restarted.get());
        org.junit.jupiter.api.Assertions.assertTrue(result.success());
        org.junit.jupiter.api.Assertions.assertEquals("enable.success", result.messageKey());
    }

    private Plugin mockPlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> "getName".equals(method.getName()) ? name : null
        );
    }
}
