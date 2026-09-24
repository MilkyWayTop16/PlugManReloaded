package ru.milkyway.plugmanreloaded.managers;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.BukkitServerMock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClassLoaderSanitizerSelfGuardTest {

    public static class TargetPluginStub extends JavaPlugin {
        public static String testStaticField = "initial_value";
    }

    @AfterEach
    void tearDown() {
        BukkitServerMock.resetServer();
    }

    @Test
    void sanitizeGuardsSelfPluginBeforeCleanup() throws Exception {
        BukkitServerMock.ensureInitialized();
        SanitizerManager cleanup = new SanitizerManager(null);

        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        TargetPluginStub selfPlugin = (TargetPluginStub) unsafe.allocateInstance(TargetPluginStub.class);
        org.bukkit.plugin.PluginDescriptionFile pdf =
                new org.bukkit.plugin.PluginDescriptionFile("PlugManReloaded", "1.0", "ru.milkyway.plugmanreloaded.PlugManReloaded");
        Field descField = JavaPlugin.class.getDeclaredField("description");
        descField.setAccessible(true);
        descField.set(selfPlugin, pdf);

        Field metaField = JavaPlugin.class.getDeclaredField("pluginMeta");
        metaField.setAccessible(true);
        metaField.set(selfPlugin, pdf);

        AtomicBoolean unregisterServicesCalled = new AtomicBoolean(false);
        ServicesManager sm = (ServicesManager) Proxy.newProxyInstance(
                ClassLoaderSanitizerSelfGuardTest.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class},
                (proxy, method, args) -> {
                    if ("unregisterAll".equals(method.getName())) {
                        unregisterServicesCalled.set(true);
                    }
                    return null;
                }
        );

        Server baseServer = Bukkit.getServer();
        Server serverProxy = (Server) Proxy.newProxyInstance(
                ClassLoaderSanitizerSelfGuardTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServicesManager" -> sm;
                    default -> method.invoke(baseServer, args);
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, serverProxy);

        TargetPluginStub.testStaticField = "preserved";
        cleanup.cleanup(selfPlugin);

        assertFalse(unregisterServicesCalled.get());
        assertEquals("preserved", TargetPluginStub.testStaticField);

        Method cleanStaticFields = SanitizerManager.class.getDeclaredMethod("cleanStaticFields", org.bukkit.plugin.Plugin.class);
        cleanStaticFields.setAccessible(true);
        cleanStaticFields.invoke(cleanup, selfPlugin);
        assertNull(TargetPluginStub.testStaticField);
    }
}
