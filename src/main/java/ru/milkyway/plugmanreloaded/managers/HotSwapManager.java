package ru.milkyway.plugmanreloaded.managers;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HotSwapManager {

    private final PlugManReloaded plugin;
    private final LifecycleManager lifecycleManager;
    private WatchService watchService;
    private Thread watchThread;

    @Getter
    private volatile boolean running = false;

    private final Map<String, Long> lastModifiedDebounce = new ConcurrentHashMap<>();
    private final Map<String, Long> ignoredFilesUntil = new ConcurrentHashMap<>();
    private ScheduledExecutorService debounceExecutor;

    public HotSwapManager(PlugManReloaded plugin, LifecycleManager lifecycleManager) {
        this.plugin = plugin;
        this.lifecycleManager = lifecycleManager;

        if (plugin.getConfigManager().isHotSwapEnabled()) {
            start();
        }
    }

    public void temporarilyIgnore(@Nullable String fileName, long durationMs) {
        if (fileName == null || fileName.isBlank()) return;
        long now = System.currentTimeMillis();
        ignoredFilesUntil.entrySet().removeIf(entry -> now > entry.getValue());
        ignoredFilesUntil.put(fileName.toLowerCase(Locale.ROOT), now + durationMs);
    }

    public boolean isTemporarilyIgnored(@Nullable String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String key = fileName.toLowerCase(Locale.ROOT);
        Long until = ignoredFilesUntil.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            ignoredFilesUntil.remove(key, until);
            return false;
        }
        return true;
    }

    public synchronized void start() {
        if (running) return;
        try {
            File pluginsDir = plugin.getDataFolder().getParentFile();
            if (pluginsDir == null || !pluginsDir.exists()) return;

            if (debounceExecutor == null || debounceExecutor.isShutdown()) {
                debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "PlugManReloaded-HotSwap-Debounce");
                    t.setDaemon(true);
                    return t;
                });
            }

            watchService = FileSystems.getDefault().newWatchService();
            pluginsDir.toPath().register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE
            );

            running = true;
            watchThread = new Thread(this::watchLoop, "PlugManReloaded-HotSwap");
            watchThread.setDaemon(true);
            watchThread.start();

            Log.info("hotswapmanager.started");
        } catch (Throwable t) {
            running = false;
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (Throwable closeError) {
                    Log.debug("hotswapmanager.watchservice-close-failed", closeError);
                }
                watchService = null;
            }
            if (debounceExecutor != null) {
                debounceExecutor.shutdownNow();
                debounceExecutor = null;
            }
            Log.error("hotswapmanager.init-failed", t, "error", t.getMessage());
        }
    }

    public synchronized void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                Log.error("hotswapmanager.close-error", "error", e.getMessage());
            }
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (debounceExecutor != null && !debounceExecutor.isShutdown()) {
            debounceExecutor.shutdownNow();
            debounceExecutor = null;
        }
        lastModifiedDebounce.clear();
    }

    public synchronized void reload() {
        boolean shouldRun = plugin.getConfigManager().isHotSwapEnabled();
        if (shouldRun) {
            if (running) {
                stop();
            }
            start();
        } else {
            if (running) {
                stop();
            }
        }
    }

    private void watchLoop() {
        try {
            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        Log.warn("hotswapmanager.overflow");
                        lifecycleManager.getJarIndex().invalidate();
                        continue;
                    }

                    Path path = (Path) event.context();
                    String fileName = path.toString();
                    String lower = fileName.toLowerCase(Locale.ROOT);

                    if (!lower.endsWith(".jar") || lower.endsWith(".tmp") || lower.endsWith(".bak")
                            || lower.endsWith(".old") || lower.endsWith(".part") || lower.endsWith(".crdownload")
                            || lower.endsWith(".uploading")) {
                        continue;
                    }

                    if (fileName.equalsIgnoreCase(plugin.getName() + ".jar") || lower.contains("plugmanreloaded")) {
                        continue;
                    }

                    if (isTemporarilyIgnored(fileName)) {
                        continue;
                    }

                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        handleFileDeleted(fileName);
                    } else {
                        scheduleDebouncedReload(fileName);
                    }
                }

                if (!key.reset()) {
                    break;
                }
            }
        } finally {
            running = false;
        }
    }

    private void scheduleDebouncedReload(String fileName) {
        if (!running || debounceExecutor == null || debounceExecutor.isShutdown()) return;
        if (isTemporarilyIgnored(fileName)) return;

        long now = System.currentTimeMillis();
        lastModifiedDebounce.put(fileName, now);

        int debounceMs = plugin.getConfigManager().getHotSwapDebounceMs();
        try {
            debounceExecutor.schedule(() -> {
                Long last = lastModifiedDebounce.get(fileName);
                if (last != null && System.currentTimeMillis() - last >= debounceMs) {
                    handleFileChanged(fileName, 0);
                }
            }, debounceMs + 50L, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            Log.error("hotswapmanager.task-rejected", "file", fileName, "error", e.getMessage());
        }
    }

    private void handleFileChanged(String fileName, int attempt) {
        if (!running || isTemporarilyIgnored(fileName)) {
            lastModifiedDebounce.remove(fileName);
            return;
        }

        File file = new File(plugin.getDataFolder().getParentFile(), fileName);
        if (!file.exists()) {
            lastModifiedDebounce.remove(fileName);
            return;
        }

        if (!JarValidator.isValidPluginJar(file)) {
            if (attempt < 8) {
                if (running && debounceExecutor != null && !debounceExecutor.isShutdown()) {
                    try {
                        debounceExecutor.schedule(() -> handleFileChanged(fileName, attempt + 1), 250L, TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException ignored) {}
                }
                return;
            }
            lastModifiedDebounce.remove(fileName);
            Log.debug("hotswapmanager.file-still-writing", "file", fileName);
            return;
        }

        lastModifiedDebounce.remove(fileName);

        JarValidator.PreFlightReport preFlight = JarValidator.validatePreFlight(file, null, false);
        if (!preFlight.isValid()) {
            Log.warn("hotswapmanager.preflight-failed", "file", fileName, "error", String.valueOf(preFlight.errorMessage()));
            return;
        }

        String declaredName = preFlight.declaredName();
        String newVersion = preFlight.declaredVersion();

        if (declaredName != null && plugin.getDownloadService().getLockManager().isLocked(declaredName)) {
            Log.debug("hotswapmanager.updating-via-installer", "plugin", declaredName);
            return;
        }

        TaskScheduler.runSync(plugin, () -> applyChange(file, fileName, declaredName, newVersion));
    }

    private void applyChange(File file, String fileName, @Nullable String declaredName, String newVersion) {
        if (!running || isTemporarilyIgnored(fileName)) return;

        Plugin target = findExistingPlugin(declaredName, fileName);
        if (target == null) {
            if (!plugin.getConfigManager().isHotSwapAllowUntrustedLoads()) {
                Log.warn("hotswapmanager.untrusted-load-blocked", "file", fileName);
                return;
            }
            loadNewPlugin(file, fileName, declaredName, newVersion);
            return;
        }

        if (lifecycleManager.isProtected(target)) {
            Log.warn("hotswapmanager.protected-skip", "plugin", target.getName());
            return;
        }

        JarValidator.PreFlightReport targetReport = JarValidator.validatePreFlight(file, target.getName(), true);
        if (!targetReport.isValid()) {
            Log.warn("hotswapmanager.apply-failed", "file", fileName, "plugin", target.getName(),
                    "error", String.valueOf(targetReport.errorMessage()));
            return;
        }

        File oldFile = lifecycleManager.getPluginFile(target);
        if (oldFile != null && !oldFile.getName().equalsIgnoreCase(fileName)) {
            upgradeToNewFile(file, fileName, target, oldFile, newVersion);
        } else {
            restartInPlace(target, fileName, newVersion);
        }
    }

    private void upgradeToNewFile(File file, String fileName, Plugin target, File oldFile, String newVersion) {
        String pluginName = target.getName();
        Map<String, String> ph = new HashMap<>();
        ph.put("plugin", pluginName);
        ph.put("old-version", PluginMetaHelper.getVersion(target));
        ph.put("new-version", newVersion);
        ph.put("version", newVersion);
        ph.put("file", fileName);
        announce("hotswap.update-triggered", ph);

        long start = System.currentTimeMillis();
        boolean cascade = plugin.getConfigManager().isHotSwapCascadeReload();
        List<String> cascadeOrder = cascade
                ? lifecycleManager.getDependencyManager().calculateCascadeOrder(pluginName, true)
                : null;

        List<String> unloadedDependents = new ArrayList<>();
        if (cascadeOrder != null && cascadeOrder.size() > 1) {
            List<String> reverseOrder = new ArrayList<>(cascadeOrder);
            Collections.reverse(reverseOrder);
            for (String depName : reverseOrder) {
                if (depName.equalsIgnoreCase(pluginName)) continue;
                Plugin depPlugin = Bukkit.getPluginManager().getPlugin(depName);
                if (depPlugin != null && depPlugin.isEnabled()) {
                    if (lifecycleManager.unload(depPlugin).success()) {
                        unloadedDependents.add(depName);
                    }
                }
            }
        }

        PluginResult unloadResult = lifecycleManager.unload(target);
        if (!unloadResult.success()) {
            for (int i = unloadedDependents.size() - 1; i >= 0; i--) {
                String depName = unloadedDependents.get(i);
                Plugin depPlugin = lifecycleManager.getPlugin(depName);
                File depFile = depPlugin != null ? lifecycleManager.getPluginFile(depPlugin) : null;
                if (depFile != null) {
                    lifecycleManager.load(depFile);
                }
            }
            Map<String, String> failPh = new HashMap<>(ph);
            failPh.putAll(unloadResult.placeholders());
            announce("hotswap.update-failed", failPh);
            return;
        }

        temporarilyIgnore(oldFile.getName(), 10000L);
        File backupFile = new File(oldFile.getParentFile(), oldFile.getName() + ".old");
        temporarilyIgnore(backupFile.getName(), 10000L);

        boolean backedUp = false;
        if (plugin.getConfigManager().isHotSwapBackupOldVersion() && oldFile.exists()) {
            backedUp = backupOrRenameOldFile(oldFile, backupFile, 3, 5);
            if (!backedUp) {
                Log.debug("hotswapmanager.old-move-failed", "file", oldFile.getName());
            }
        } else if (oldFile.exists()) {
            deleteFileWithRetry(oldFile, 3, 5);
        }

        PluginResult loadResult = lifecycleManager.load(file);
        long elapsed = System.currentTimeMillis() - start;

        if (loadResult.success()) {
            if (cascadeOrder != null && cascadeOrder.size() > 1) {
                restartDependents(cascadeOrder, pluginName);
            }
            lifecycleManager.getJarIndex().invalidate();
            announceResult("hotswap.update-success", ph, loadResult, elapsed);
            return;
        }

        Log.warn("hotswapmanager.new-version-load-failed", "plugin", pluginName);
        isolateFailedUpload(file, oldFile);
        if (backedUp && backupFile.exists()) {
            backupOrRenameOldFile(backupFile, oldFile, 3, 5);
            lifecycleManager.load(oldFile);
        }
        if (!unloadedDependents.isEmpty()) {
            restartDependents(cascadeOrder != null ? cascadeOrder : unloadedDependents, pluginName);
        }
        lifecycleManager.getJarIndex().invalidate();
        announceResult("hotswap.update-failed", ph, loadResult, elapsed);
    }

    private void restartInPlace(Plugin target, String fileName, String newVersion) {
        Map<String, String> ph = new HashMap<>();
        ph.put("plugin", target.getName());
        ph.put("version", newVersion);
        ph.put("file", fileName);
        announce("hotswap.triggered", ph);

        long start = System.currentTimeMillis();
        PluginResult result = plugin.getConfigManager().isHotSwapCascadeReload()
                ? lifecycleManager.cascadeRestart(target)
                : lifecycleManager.restart(target);
        long elapsed = System.currentTimeMillis() - start;

        lifecycleManager.getJarIndex().invalidate();
        announceResult(result.success() ? "hotswap.restart-success" : "errors.reload-failed", ph, result, elapsed);
    }

    private void loadNewPlugin(File file, String fileName, @Nullable String declaredName, String newVersion) {
        Map<String, String> ph = new HashMap<>();
        ph.put("plugin", declaredName != null ? declaredName : stripExtension(fileName));
        ph.put("version", newVersion);
        ph.put("file", fileName);
        announce("hotswap.load-triggered", ph);

        long start = System.currentTimeMillis();
        PluginResult loadResult = lifecycleManager.load(file);
        long elapsed = System.currentTimeMillis() - start;

        lifecycleManager.getJarIndex().invalidate();
        announceResult(loadResult.success() ? "hotswap.load-success" : "errors.load-failed", ph, loadResult, elapsed);
    }

    private void restartDependents(List<String> cascadeOrder, String pluginName) {
        for (String depName : cascadeOrder) {
            if (depName.equalsIgnoreCase(pluginName)) continue;
            Plugin depPlugin = Bukkit.getPluginManager().getPlugin(depName);
            if (depPlugin != null) {
                lifecycleManager.restart(depPlugin);
            }
        }
    }

    private void announce(String actionKey, Map<String, String> placeholders) {
        plugin.getConfigManager().executeActions(Bukkit.getConsoleSender(), actionKey, placeholders);
    }

    private void announceResult(String actionKey, Map<String, String> base, PluginResult result, long elapsed) {
        Map<String, String> ph = new HashMap<>(base);
        ph.put("time", String.valueOf(elapsed));
        ph.putAll(result.placeholders());
        announce(actionKey, ph);
    }

    static void isolateFailedUpload(File file, File oldFile) {
        if (file == null || !file.exists() || file.equals(oldFile)) return;
        try {
            File failedFile = new File(file.getParentFile(), file.getName() + ".failed");
            Files.move(file.toPath(), failedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            deleteFileWithRetry(file, 5, 50);
        }
    }

    private static boolean backupOrRenameOldFile(File src, File dst, int maxAttempts, long sleepMs) {
        for (int i = 0; i < maxAttempts; i++) {
            if (dst.exists()) {
                try {
                    Files.deleteIfExists(dst.toPath());
                } catch (Throwable t) {
                    Log.debug("hotswapmanager.old-backup-delete-failed", t, "file", dst.getName());
                }
            }
            try {
                Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (Throwable t) {
                try {
                    Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return true;
                } catch (Throwable fallback) {
                    Log.debug("hotswapmanager.move-attempt-failed", fallback, "attempt", String.valueOf(i + 1), "file", src.getName());
                }
            }
            if (src.renameTo(dst)) {
                return true;
            }
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return !src.exists() || dst.exists();
    }

    private static boolean deleteFileWithRetry(File file, int maxAttempts, long sleepMs) {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                if (Files.deleteIfExists(file.toPath())) {
                    return true;
                }
            } catch (Throwable t) {
                Log.debug("hotswapmanager.delete-attempt-failed", t, "attempt", String.valueOf(i + 1), "file", file.getName());
            }
            if (file.delete() || !file.exists()) {
                return true;
            }
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return !file.exists();
    }

    private @Nullable Plugin findExistingPlugin(@Nullable String declaredName, String fileName) {
        if (declaredName != null) {
            Plugin existing = lifecycleManager.getPlugin(declaredName);
            if (existing != null) {
                return existing;
            }
        }
        return lifecycleManager.getPlugin(fileName);
    }

    private void handleFileDeleted(String fileName) {
        lastModifiedDebounce.remove(fileName);
        if (!plugin.getConfigManager().isHotSwapAutoUnloadOnDelete()) return;
        if (isTemporarilyIgnored(fileName)) return;

        TaskScheduler.runSync(plugin, () -> {
            if (!running || isTemporarilyIgnored(fileName)) return;

            File pluginsDir = plugin.getDataFolder() != null ? plugin.getDataFolder().getParentFile() : null;
            if (pluginsDir == null) return;
            File file = new File(pluginsDir, fileName);
            if (file.exists()) return;

            Plugin existing = lifecycleManager.getPlugin(fileName);
            if (existing == null || lifecycleManager.isProtected(existing)) return;

            String pluginName = existing.getName();
            String version = PluginMetaHelper.getVersion(existing);

            Map<String, String> ph = new HashMap<>();
            ph.put("plugin", pluginName);
            ph.put("version", version);
            ph.put("file", fileName);

            plugin.getConfigManager().executeActions(Bukkit.getConsoleSender(), "hotswap.delete-triggered", ph);

            long start = System.currentTimeMillis();
            PluginResult unloadResult = lifecycleManager.unload(existing);
            long elapsed = System.currentTimeMillis() - start;

            lifecycleManager.getJarIndex().invalidate();

            Map<String, String> finishPh = new HashMap<>(ph);
            finishPh.put("time", String.valueOf(elapsed));

            if (unloadResult.success()) {
                plugin.getConfigManager().executeActions(Bukkit.getConsoleSender(), "hotswap.delete-success", finishPh);
            } else {
                finishPh.putAll(unloadResult.placeholders());
                plugin.getConfigManager().executeActions(Bukkit.getConsoleSender(), "hotswap.delete-failed", finishPh);
            }
        });
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}