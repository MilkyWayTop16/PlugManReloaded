package ru.milkyway.plugmanreloaded;

import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.managers.HotSwapManager;
import ru.milkyway.plugmanreloaded.utils.UpdateChecker;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlugManReloadedShutdownTest {

    static class TrackingHotSwapManager extends HotSwapManager {
        final AtomicBoolean stopped = new AtomicBoolean(false);

        TrackingHotSwapManager() {
            super(null, null);
        }

        @Override
        public synchronized void stop() {
            stopped.set(true);
        }
    }

    static class TrackingUpdateChecker extends UpdateChecker {
        final AtomicBoolean shutdown = new AtomicBoolean(false);

        TrackingUpdateChecker() {
            super(null);
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
        }
    }

    static class TrackingConfirmationManager extends ru.milkyway.plugmanreloaded.managers.ConfirmationManager {
        final AtomicBoolean shutdown = new AtomicBoolean(false);

        TrackingConfirmationManager() {
            super();
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
            super.shutdown();
        }
    }

    static class TrackingManualSources extends ru.milkyway.plugmanreloaded.update.input.ManualSources {
        final AtomicBoolean shutdown = new AtomicBoolean(false);

        TrackingManualSources() {
            super(null);
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
            super.shutdown();
        }
    }

    @AfterEach
    void tearDown() {
        BukkitServerMock.resetServer();
    }

    @Test
    void onDisableStopsSubsystemsOnPartialInit() throws Exception {
        BukkitServerMock.ensureInitialized();

        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);

        TrackingHotSwapManager hsm = (TrackingHotSwapManager) unsafe.allocateInstance(TrackingHotSwapManager.class);
        Field hsmStopped = TrackingHotSwapManager.class.getDeclaredField("stopped");
        hsmStopped.setAccessible(true);
        AtomicBoolean hsmFlag = new AtomicBoolean(false);
        hsmStopped.set(hsm, hsmFlag);

        TrackingUpdateChecker checker = (TrackingUpdateChecker) unsafe.allocateInstance(TrackingUpdateChecker.class);
        Field checkerShutdown = TrackingUpdateChecker.class.getDeclaredField("shutdown");
        checkerShutdown.setAccessible(true);
        AtomicBoolean checkerFlag = new AtomicBoolean(false);
        checkerShutdown.set(checker, checkerFlag);

        ServicesManager sm = (ServicesManager) Proxy.newProxyInstance(
                PlugManReloadedShutdownTest.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class},
                (proxy, method, args) -> null
        );

        Server serverProxy = (Server) Proxy.newProxyInstance(
                PlugManReloadedShutdownTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServicesManager" -> sm;
                    case "isPrimaryThread" -> true;
                    default -> method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        Field jpServer = JavaPlugin.class.getDeclaredField("server");
        jpServer.setAccessible(true);
        jpServer.set(plugin, serverProxy);

        Field hsmField = PlugManReloaded.class.getDeclaredField("hotSwapManager");
        hsmField.setAccessible(true);
        hsmField.set(plugin, hsm);

        Field checkerField = PlugManReloaded.class.getDeclaredField("updateChecker");
        checkerField.setAccessible(true);
        checkerField.set(plugin, checker);

        TrackingConfirmationManager confirmation = (TrackingConfirmationManager) unsafe.allocateInstance(TrackingConfirmationManager.class);
        Field confirmShutdown = TrackingConfirmationManager.class.getDeclaredField("shutdown");
        confirmShutdown.setAccessible(true);
        AtomicBoolean confirmFlag = new AtomicBoolean(false);
        confirmShutdown.set(confirmation, confirmFlag);

        Field confirmField = PlugManReloaded.class.getDeclaredField("confirmationManager");
        confirmField.setAccessible(true);
        confirmField.set(plugin, confirmation);

        TrackingManualSources manualSources = (TrackingManualSources) unsafe.allocateInstance(TrackingManualSources.class);
        Field msShutdown = TrackingManualSources.class.getDeclaredField("shutdown");
        msShutdown.setAccessible(true);
        AtomicBoolean msFlag = new AtomicBoolean(false);
        msShutdown.set(manualSources, msFlag);

        Field msField = PlugManReloaded.class.getDeclaredField("manualSources");
        msField.setAccessible(true);
        msField.set(plugin, manualSources);

        Field initField = PlugManReloaded.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        initField.setBoolean(plugin, false);

        plugin.onDisable();

        assertTrue(hsmFlag.get());
        assertTrue(checkerFlag.get());
        assertTrue(confirmFlag.get());
        assertTrue(msFlag.get());
    }
}
