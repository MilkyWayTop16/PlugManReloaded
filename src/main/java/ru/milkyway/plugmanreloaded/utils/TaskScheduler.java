package ru.milkyway.plugmanreloaded.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class TaskScheduler {

    private static final long TICKS_PER_HOUR = 72000L;

    private static volatile Object foliaGlobalRegionScheduler;
    private static volatile Object foliaAsyncScheduler;

    private TaskScheduler() {}

    private static Object getFoliaGlobalRegionScheduler() {
        if (foliaGlobalRegionScheduler == null) {
            synchronized (TaskScheduler.class) {
                if (foliaGlobalRegionScheduler == null) {
                    foliaGlobalRegionScheduler = ReflectionHelper.invokeMethodOrThrow(Bukkit.getServer(), "getGlobalRegionScheduler");
                }
            }
        }
        return foliaGlobalRegionScheduler;
    }

    private static Object getFoliaAsyncScheduler() {
        if (foliaAsyncScheduler == null) {
            synchronized (TaskScheduler.class) {
                if (foliaAsyncScheduler == null) {
                    foliaAsyncScheduler = ReflectionHelper.invokeMethodOrThrow(Bukkit.getServer(), "getAsyncScheduler");
                }
            }
        }
        return foliaAsyncScheduler;
    }

    private static Consumer<Object> asConsumer(Runnable task) {
        return scheduledTask -> task.run();
    }

    public static void runSync(Plugin plugin, @Nullable Runnable task) {
        if (task == null || plugin == null) return;

        if (PlatformDetector.isFolia()) {
            ReflectionHelper.invokeMethodOrThrow(getFoliaGlobalRegionScheduler(), "run", plugin, asConsumer(task));
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runForEntity(@Nullable Plugin plugin, @Nullable Entity entity, @Nullable Runnable task) {
        if (task == null || entity == null) return;

        if (PlatformDetector.isFolia()) {
            try {
                Boolean owned = ReflectionHelper.invokeMethod(Bukkit.getServer(), "isOwnedByCurrentRegion", entity);
                if (Boolean.TRUE.equals(owned)) {
                    task.run();
                    return;
                }
                Object scheduler = ReflectionHelper.invokeMethod(entity, "getScheduler");
                if (scheduler != null && plugin != null) {
                    ReflectionHelper.invokeMethod(scheduler, "execute", plugin, task, null, 1L);
                    return;
                }
            } catch (Throwable ignored) {
            }
            if (Bukkit.getServer() == null) {
                task.run();
            }
            return;
        }

        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin != null && Bukkit.getScheduler() != null) {
            Bukkit.getScheduler().runTask(plugin, task);
        } else {
            task.run();
        }
    }

    public static @Nullable Object runSyncLater(Plugin plugin, @Nullable Runnable task, long delayTicks) {
        if (task == null || plugin == null) return null;

        long safeDelayTicks = Math.max(1L, delayTicks);
        if (PlatformDetector.isFolia()) {
            return ReflectionHelper.invokeMethodOrThrow(getFoliaGlobalRegionScheduler(), "runDelayed",
                    plugin, asConsumer(task), safeDelayTicks);
        }

        return Bukkit.getScheduler().runTaskLater(plugin, task, safeDelayTicks);
    }

    public static void runAsync(Plugin plugin, @Nullable Runnable task) {
        if (task == null || plugin == null) return;

        if (PlatformDetector.isFolia()) {
            ReflectionHelper.invokeMethodOrThrow(getFoliaAsyncScheduler(), "runNow", plugin, asConsumer(task));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public static @Nullable Object runAsyncTimer(Plugin plugin, @Nullable Runnable task, long delay, long period, TimeUnit unit) {
        if (task == null || plugin == null || unit == null) return null;

        long safeDelay = Math.max(1L, delay);
        long safePeriod = Math.max(1L, period);

        if (PlatformDetector.isFolia()) {
            return ReflectionHelper.invokeMethodOrThrow(getFoliaAsyncScheduler(), "runAtFixedRate",
                    plugin, asConsumer(task), safeDelay, safePeriod, unit);
        }

        long delayTicks = Math.max(1L, unit.toMillis(safeDelay) / 50L);
        long periodTicks = Math.max(1L, unit.toMillis(safePeriod) / 50L);

        if (Bukkit.getServer() == null || Bukkit.getScheduler() == null) {
            return null;
        }

        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
    }

    public static @Nullable Object runAsyncTimer(Plugin plugin, @Nullable Runnable task, long delayHours, long periodHours) {
        return runAsyncTimer(plugin, task, delayHours, periodHours, TimeUnit.HOURS);
    }

    public static void cancelTask(@Nullable Object taskHandle) {
        if (taskHandle == null) return;

        if (taskHandle instanceof BukkitTask bukkitTask) {
            bukkitTask.cancel();
        } else {
            ReflectionHelper.invokeMethod(taskHandle, "cancel");
        }
    }
}
