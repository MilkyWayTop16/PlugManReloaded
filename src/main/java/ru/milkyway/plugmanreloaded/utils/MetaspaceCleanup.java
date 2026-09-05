package ru.milkyway.plugmanreloaded.utils;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MetaspaceCleanup {

    private MetaspaceCleanup() {}

    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    public static void requestDeferred(@Nullable PlugManReloaded plugin) {
        if (plugin == null || !plugin.isEnabled()) {
            runNow();
            return;
        }
        if (!SCHEDULED.compareAndSet(false, true)) return;

        try {
            Object task = TaskScheduler.runSyncLater(plugin, () -> {
                if (SCHEDULED.compareAndSet(true, false)) {
                    System.gc();
                }
            }, 1L);
            if (task == null) {
                runNow();
            }
        } catch (Throwable t) {
            runNow();
        }
    }

    public static void runNow() {
        SCHEDULED.set(false);
        System.gc();
    }

    static boolean hasPendingRequest() {
        return SCHEDULED.get();
    }

    static void resetForTests() {
        SCHEDULED.set(false);
    }
}

