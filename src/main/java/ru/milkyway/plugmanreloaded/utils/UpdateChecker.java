package ru.milkyway.plugmanreloaded.utils;

import com.google.gson.JsonObject;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.update.HttpJson;
import ru.milkyway.plugmanreloaded.update.VersionCompare;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Getter
public class UpdateChecker implements Listener {

    private final PlugManReloaded plugin;

    private volatile boolean updateAvailable = false;
    private volatile String latestVersion = null;
    private final AtomicLong lastCheckTime = new AtomicLong(0);

    private static final long MIN_CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(30);
    private static final String GITHUB_API_URL = "https://api.github.com/repos/MilkyWayTop16/PlugManReloaded/releases/latest";

    private Object periodicTaskHandle;

    public UpdateChecker(PlugManReloaded plugin) {
        this.plugin = plugin;
        startChecker();
    }

    public void reload() {
        updateAvailable = false;
        latestVersion = null;
        lastCheckTime.set(0);
        cancelPeriodicTask();
        startChecker();
    }

    public void shutdown() {
        cancelPeriodicTask();
    }

    private void startChecker() {
        if (!plugin.getConfigManager().isUpdateCheckerEnabled()) return;

        checkForUpdatesAsync(notifyOnStart());

        if (notifyPeriodically()) {
            int hours = Math.max(1, plugin.getConfigManager().getUpdatePeriodicIntervalHours());
            periodicTaskHandle = TaskScheduler.runAsyncTimer(plugin, () -> checkForUpdatesAsync(true), hours, hours);
        }
    }

    private String notifyMode() {
        String mode = plugin.getConfigManager().getUpdateNotifyMode();
        return mode == null ? "both" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private boolean notifyOnStart() {
        String mode = notifyMode();
        return mode.equals("on-start") || mode.equals("both");
    }

    private boolean notifyOnJoin() {
        String mode = notifyMode();
        return mode.equals("on-join") || mode.equals("both");
    }

    private boolean notifyPeriodically() {
        String mode = notifyMode();
        return mode.equals("periodic") || mode.equals("both");
    }

    private void cancelPeriodicTask() {
        if (periodicTaskHandle != null) {
            TaskScheduler.cancelTask(periodicTaskHandle);
            periodicTaskHandle = null;
        }
    }

    public void checkForUpdatesAsync(boolean announce) {
        long now = System.currentTimeMillis();
        long previous = lastCheckTime.get();
        if (now - previous < MIN_CHECK_INTERVAL && latestVersion != null) return;
        if (!lastCheckTime.compareAndSet(previous, now)) return;

        TaskScheduler.runAsync(plugin, () -> {
            try {
                HttpJson.Response response = HttpJson.get(GITHUB_API_URL);
                if (response.ok() && response.body().isJsonObject()) {
                    JsonObject obj = response.body().getAsJsonObject();
                    if (obj.has("tag_name") && !obj.get("tag_name").isJsonNull()) {
                        String tag = PluginMetaHelper.cleanVersion(obj.get("tag_name").getAsString());
                        this.latestVersion = tag;
                        String current = PluginMetaHelper.getVersion(plugin);
                        if (VersionCompare.isNewer(tag, current)) {
                            this.updateAvailable = true;
                            if (announce) {
                                announceUpdate();
                            }
                        }
                    }
                }
            } catch (Exception t) {
                Log.warn("updatechecker.check-failed", t, "error", t.getMessage());
            }
        });
    }

    private void announceUpdate() {
        TaskScheduler.runSync(plugin, () -> {
            Map<String, String> placeholders = updatePlaceholders();
            plugin.getConfigManager().executeActions(Bukkit.getConsoleSender(), "update.available", placeholders);

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (canReceiveNotify(online)) {
                    plugin.getConfigManager().executeActions(online, "update.available", placeholders);
                }
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable || !plugin.getConfigManager().isUpdateCheckerEnabled()) return;
        if (!notifyOnJoin()) return;

        Player player = event.getPlayer();
        if (!canReceiveNotify(player)) return;

        TaskScheduler.runSyncLater(plugin, () -> sendNotify(player), 40L);
    }

    private boolean canReceiveNotify(Player player) {
        return player.hasPermission("plugmanreloaded.admin")
                || player.hasPermission("plugmanreloaded.notify")
                || player.isOp();
    }

    private Map<String, String> updatePlaceholders() {
        String rawDownloadUrl = "https://github.com/MilkyWayTop16/PlugManReloaded/releases";
        String formattedDownloadUrl = formatLink(rawDownloadUrl);
        Map<String, String> map = new HashMap<>();
        map.put("latest", latestVersion != null ? latestVersion : "Unknown");
        map.put("current", PluginMetaHelper.getVersion(plugin));
        map.put("raw-url", rawDownloadUrl);
        map.put("url", formattedDownloadUrl);
        return map;
    }

    private String formatLink(@Nullable String url) {
        if (url == null || url.isBlank()) return "";
        FileConfiguration config = plugin.getConfigManager().getMessagesConfig();
        List<String> hoverList = config.getStringList("actions.update.link.hover");
        if (hoverList.isEmpty()) {
            if (config.isList("actions.link.hover")) {
                hoverList = config.getStringList("actions.link.hover");
            }
        }
        String rawHover;
        if (!hoverList.isEmpty()) {
            rawHover = String.join("\n", hoverList);
        } else {
            rawHover = LogCatalog.get("updatechecker.hover-fallback");
        }
        String miniHover = HexColors.toMiniMessage(rawHover);
        return "<click:open_url:\"" + url.replace("\"", "") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\"><underlined>" + url + "</underlined></hover></click>";
    }

    private void sendNotify(Player player) {
        if (player.isOnline()) {
            plugin.getConfigManager().executeActions(player, "update.available", updatePlaceholders());
        }
    }
}

