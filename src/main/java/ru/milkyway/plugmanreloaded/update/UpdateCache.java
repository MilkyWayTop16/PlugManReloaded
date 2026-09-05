package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UpdateCache {

    private record Entry(Object value, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile long ttlMillis;

    public UpdateCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public void setTtlMillis(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(String key, Class<T> type) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expired()) {
            entries.remove(key);
            return null;
        }
        Object value = entry.value();
        return type.isInstance(value) ? (T) value : null;
    }

    public void put(String key, Object value) {
        put(key, value, ttlMillis);
    }

    public void put(@Nullable String key, Object value, long customTtlMillis) {
        if (key == null || value == null) return;
        entries.put(key, new Entry(value, System.currentTimeMillis() + customTtlMillis));
    }

    public void remove(String key) {
        if (key != null) entries.remove(key);
    }

    public void sweepExpired() {
        entries.entrySet().removeIf(e -> e.getValue().expired());
    }

    public void clearVersions() {
        entries.keySet().removeIf(k -> k.contains(":versions:") || k.contains(":releases:") || k.contains(":builds:") || k.contains(":build:") || k.contains(":metadata:"));
    }

    public void invalidateVersions(@Nullable String projectRef) {
        if (projectRef == null || projectRef.isBlank()) return;
        String lower = projectRef.toLowerCase(Locale.ROOT);
        entries.keySet().removeIf(k -> {
            if (k.endsWith(":miss")) return false;
            String kl = k.toLowerCase(Locale.ROOT);
            return kl.contains(":versions:" + lower) || kl.contains(":releases:" + lower)
                    || kl.contains(":builds:" + lower) || kl.contains(":build:" + lower)
                    || kl.contains(":metadata:" + lower);
        });
    }

    public void clearMisses() {
        entries.keySet().removeIf(k -> k.endsWith(":miss"));
    }

    public void invalidateMiss(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return;
        String lower = pluginName.toLowerCase(Locale.ROOT);
        entries.keySet().removeIf(k -> {
            if (!k.endsWith(":miss")) return false;
            String[] segments = k.toLowerCase(Locale.ROOT).split(":");
            for (int i = 0; i < segments.length - 1; i++) {
                if (segments[i].equals(lower)) return true;
            }
            return false;
        });
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public void saveMisses(@Nullable File file) {
        if (file == null) return;

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            Entry value = entry.getValue();
            if (entry.getKey().endsWith(":miss") && Boolean.TRUE.equals(value.value()) && !value.expired()) {
                lines.add(entry.getKey() + "\t" + value.expiresAt());
            }
        }

        try {
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception t) {
            Log.debug("updatecache.misses-save-failed", t);
        }
    }

    public void loadMisses(@Nullable File file) {
        if (file == null || !file.isFile()) return;

        try {
            long now = System.currentTimeMillis();
            int loaded = 0;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                int tab = line.lastIndexOf('\t');
                if (tab < 0) continue;

                String key = line.substring(0, tab);
                if (!key.endsWith(":miss")) continue;

                long expiresAt;
                try {
                    expiresAt = Long.parseLong(line.substring(tab + 1));
                } catch (NumberFormatException malformed) {
                    Log.debug("updatecache.corrupted-miss-entry", "line", line);
                    continue;
                }
                if (expiresAt <= now) continue;

                entries.put(key, new Entry(Boolean.TRUE, expiresAt));
                loaded++;
            }
            if (loaded > 0) {
                Log.debug("updatecache.misses-restored", "count", String.valueOf(loaded));
            }
        } catch (Exception t) {
            Log.debug("updatecache.misses-load-failed", t);
        }
    }
}

