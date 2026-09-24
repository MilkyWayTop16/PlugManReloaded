package ru.milkyway.plugmanreloaded.concurrency;

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
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConcurrentConfirmationTest {

    private static class DummySender implements CommandSender {
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
    void testConcurrentConfirmationDoubleExecutionPrevention() throws InterruptedException {
        ConfirmationManager manager = new ConfirmationManager();
        CommandSender sender = new DummySender();

        String token = manager.createSession(sender, "delete", "Essentials");

        int threadCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCounter = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean consumed = manager.validateAndConsume(sender, "delete", "Essentials", token);
                    if (consumed) {
                        successCounter.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(1, successCounter.get(), "Exactly one concurrent thread must succeed in consuming the confirmation token");
    }
}
