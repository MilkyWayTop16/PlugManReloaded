package ru.milkyway.plugmanreloaded.download;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DownloadLocks {

    private final ConcurrentHashMap<String, Long> activeLocks = new ConcurrentHashMap<>();
    private static final long LOCK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60);

    public boolean tryLock(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return false;
        String key = pluginName.toLowerCase(Locale.ROOT).trim();
        long now = System.currentTimeMillis();

        boolean[] acquired = {false};
        activeLocks.compute(key, (k, existing) -> {
            if (existing != null && (now - existing) < LOCK_TIMEOUT_MS) {
                return existing;
            }
            acquired[0] = true;
            return now;
        });

        return acquired[0];
    }

    public void unlock(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return;
        activeLocks.remove(pluginName.toLowerCase(Locale.ROOT).trim());
    }

    public boolean isLocked(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return false;
        Long existing = activeLocks.get(pluginName.toLowerCase(Locale.ROOT).trim());
        return existing != null && (System.currentTimeMillis() - existing) < LOCK_TIMEOUT_MS;
    }
}

