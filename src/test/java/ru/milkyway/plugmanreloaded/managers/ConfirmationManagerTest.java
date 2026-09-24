package ru.milkyway.plugmanreloaded.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.BukkitServerMock;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ConfirmationManagerTest {

    private static class TestSender implements CommandSender {
        @Override public void sendMessage(@NotNull String message) {}
        @Override public void sendMessage(@NotNull String... messages) {}
        @Override public void sendMessage(@Nullable UUID sender, @NotNull String message) {}
        @Override public void sendMessage(@Nullable UUID sender, @NotNull String... messages) {}
        @Override public @NotNull Server getServer() { return Bukkit.getServer(); }
        @Override public @NotNull String getName() { return "CONSOLE"; }
        @Override public @NotNull CommandSender.Spigot spigot() { return new CommandSender.Spigot(); }
        @Override public @NotNull Component name() { return Component.text("CONSOLE"); }
        @Override public boolean isPermissionSet(@NotNull String name) { return true; }
        @Override public boolean isPermissionSet(@NotNull Permission perm) { return true; }
        @Override public boolean hasPermission(@NotNull String name) { return true; }
        @Override public boolean hasPermission(@NotNull Permission perm) { return true; }
        @Override public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value) { return null; }
        @Override public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin) { return null; }
        @Override public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value, int ticks) { return null; }
        @Override public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, int ticks) { return null; }
        @Override public void removeAttachment(@NotNull PermissionAttachment attachment) {}
        @Override public void recalculatePermissions() {}
        @Override public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() { return Set.of(); }
        @Override public boolean isOp() { return true; }
        @Override public void setOp(boolean value) {}
    }

    @Test
    void testLooksLikeToken() {
        assertTrue(ConfirmationManager.looksLikeToken("a1b2c3"));
        assertTrue(ConfirmationManager.looksLikeToken("123456"));
        assertTrue(ConfirmationManager.looksLikeToken("ABCDEF"));

        assertFalse(ConfirmationManager.looksLikeToken("1234"));
        assertFalse(ConfirmationManager.looksLikeToken("not-a-token"));
        assertFalse(ConfirmationManager.looksLikeToken(null));
        assertFalse(ConfirmationManager.looksLikeToken(""));
    }

    @Test
    void testSessionLifecycleAndValidation() {
        ConfirmationManager manager = new ConfirmationManager();
        CommandSender sender = new TestSender();

        String token = manager.createSession(sender, "delete", "Essentials", "extraData");
        assertNotNull(token);
        assertEquals(6, token.length());

        assertEquals("extraData", manager.peekPayload(sender, "delete", "Essentials"));

        assertFalse(manager.validateAndConsume(sender, "reload", "Essentials", token));
        assertFalse(manager.validateAndConsume(sender, "delete", "Vault", token));
        assertFalse(manager.validateAndConsume(sender, "delete", "Essentials", "wrongToken"));

        assertTrue(manager.validateAndConsume(sender, "delete", "Essentials", token));

        assertFalse(manager.validateAndConsume(sender, "delete", "Essentials", token));
    }

    @Test
    void testConsumeIfPresent() {
        ConfirmationManager manager = new ConfirmationManager();
        CommandSender sender = new TestSender();

        String token = manager.createSession(sender, "reload", "Vault");
        assertNotNull(token);

        manager.consumeIfPresent(sender, "reload", "Vault");
        assertFalse(manager.validateAndConsume(sender, "reload", "Vault", token));
    }

    @Test
    void testValidateAndConsumeTokenless() {
        ConfirmationManager manager = new ConfirmationManager();
        CommandSender sender = new TestSender();

        assertFalse(manager.validateAndConsume(sender, "unload", "Vault", null));
        assertFalse(manager.validateAndConsume(sender, "unload", "Vault", "   "));

        String token = manager.createSession(sender, "unload", "Vault");
        assertNotNull(token);

        assertFalse(manager.validateAndConsume(sender, "delete", "Vault", null));
        assertFalse(manager.validateAndConsume(sender, "unload", "OtherPlugin", null));

        assertTrue(manager.validateAndConsume(sender, "unload", "Vault", null));
        assertFalse(manager.validateAndConsume(sender, "unload", "Vault", null));
    }

    @Test
    void testSessionExpiration() {
        ConfirmationManager manager = new ConfirmationManager();
        CommandSender sender = new TestSender();

        String token = manager.createSession(sender, "delete", "Vault");
        assertNotNull(token);

        ConfirmationManager.ConfirmationSession expiredSession = new ConfirmationManager.ConfirmationSession(
                "CONSOLE", "delete", "Vault", token, null, System.currentTimeMillis() - 65_000L
        );
        assertTrue(expiredSession.isExpired());

        ConfirmationManager.ConfirmationSession freshSession = new ConfirmationManager.ConfirmationSession(
                "CONSOLE", "delete", "Vault", token, null, System.currentTimeMillis()
        );
        assertFalse(freshSession.isExpired());
    }

    @Test
    void testShutdownCleansUpState() {
        ConfirmationManager manager = new ConfirmationManager();
        CommandSender sender = new TestSender();
        manager.createSession(sender, "delete", "Vault");

        manager.shutdown();
        assertFalse(manager.hasActiveCleanupTask());
    }

    @Test
    void testInitializationWithPlugin() {
        BukkitServerMock.ensureInitialized();
        org.bukkit.scheduler.BukkitTask mockTask = (org.bukkit.scheduler.BukkitTask) java.lang.reflect.Proxy.newProxyInstance(
                ConfirmationManagerTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.scheduler.BukkitTask.class},
                (proxy, method, args) -> null
        );
        org.bukkit.scheduler.BukkitScheduler mockScheduler = (org.bukkit.scheduler.BukkitScheduler) java.lang.reflect.Proxy.newProxyInstance(
                ConfirmationManagerTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.scheduler.BukkitScheduler.class},
                (proxy, method, args) -> mockTask
        );
        Server mockServer = (Server) java.lang.reflect.Proxy.newProxyInstance(
                ConfirmationManagerTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getScheduler" -> mockScheduler;
                    default -> method.getReturnType().isPrimitive() ? 0 : null;
                }
        );
        org.bukkit.plugin.Plugin mockPlugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                ConfirmationManagerTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> mockServer;
                    case "getName" -> "TestPlugin";
                    case "isEnabled" -> true;
                    default -> null;
                }
        );

        java.lang.reflect.Field serverField;
        Object originalServer = null;
        try {
            serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            originalServer = serverField.get(null);
            serverField.set(null, mockServer);

            ConfirmationManager manager = new ConfirmationManager(mockPlugin);
            assertTrue(manager.hasActiveCleanupTask());

            manager.shutdown();
            assertFalse(manager.hasActiveCleanupTask());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (originalServer != null) {
                try {
                    serverField = Bukkit.class.getDeclaredField("server");
                    serverField.setAccessible(true);
                    serverField.set(null, originalServer);
                } catch (Exception ignored) {}
            }
        }
    }

    @Test
    void testFoliaInitializationUsesAsyncSchedulerWithoutBukkitScheduler() throws Exception {
        java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        java.lang.reflect.Field foliaField = ru.milkyway.plugmanreloaded.bridge.PlatformDetector.class.getDeclaredField("FOLIA");
        long foliaOffset = unsafe.staticFieldOffset(foliaField);
        Object foliaBase = unsafe.staticFieldBase(foliaField);
        boolean originalFolia = unsafe.getBoolean(foliaBase, foliaOffset);

        java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object originalServer = serverField.get(null);

        try {
            unsafe.putBoolean(foliaBase, foliaOffset, true);

            java.util.concurrent.atomic.AtomicBoolean asyncScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean taskCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

            Object mockScheduledTask = java.lang.reflect.Proxy.newProxyInstance(
                    ConfirmationManagerTest.class.getClassLoader(),
                    new Class<?>[]{io.papermc.paper.threadedregions.scheduler.ScheduledTask.class},
                    (proxy, method, args) -> {
                        if ("cancel".equals(method.getName())) {
                            taskCancelled.set(true);
                        }
                        return null;
                    }
            );

            Object mockAsyncScheduler = java.lang.reflect.Proxy.newProxyInstance(
                    ConfirmationManagerTest.class.getClassLoader(),
                    new Class<?>[]{io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class},
                    (proxy, method, args) -> {
                        if ("runAtFixedRate".equals(method.getName())) {
                            asyncScheduled.set(true);
                            return mockScheduledTask;
                        }
                        return null;
                    }
            );

            Server foliaServer = (Server) java.lang.reflect.Proxy.newProxyInstance(
                    ConfirmationManagerTest.class.getClassLoader(),
                    new Class<?>[]{Server.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getScheduler" -> throw new UnsupportedOperationException("The Bukkit scheduler may not be used on Folia!");
                        case "getAsyncScheduler" -> mockAsyncScheduler;
                        default -> method.getReturnType().isPrimitive() ? 0 : null;
                    }
            );

            serverField.set(null, foliaServer);

            org.bukkit.plugin.Plugin mockPlugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                    ConfirmationManagerTest.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.plugin.Plugin.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getServer" -> foliaServer;
                        case "getName" -> "TestPlugin";
                        case "isEnabled" -> true;
                        default -> null;
                    }
            );

            ConfirmationManager manager = new ConfirmationManager(mockPlugin);
            assertTrue(asyncScheduled.get());
            assertTrue(manager.hasActiveCleanupTask());

            manager.shutdown();
            assertTrue(taskCancelled.get());
            assertFalse(manager.hasActiveCleanupTask());

        } finally {
            unsafe.putBoolean(foliaBase, foliaOffset, originalFolia);
            serverField.set(null, originalServer);
            java.lang.reflect.Field taskSchedulerFolia = TaskScheduler.class.getDeclaredField("foliaAsyncScheduler");
            taskSchedulerFolia.setAccessible(true);
            taskSchedulerFolia.set(null, null);
        }
    }
}
