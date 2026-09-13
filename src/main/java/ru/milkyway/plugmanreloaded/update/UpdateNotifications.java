package ru.milkyway.plugmanreloaded.update;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.event.PluginUpdateFoundEvent;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class UpdateNotifications {

    private final PlugManReloaded plugin;
    private final UpdateService updateService;

    public UpdateNotifications(PlugManReloaded plugin, UpdateService updateService) {
        this.plugin = plugin;
        this.updateService = updateService;
    }

    public void checkOnStartIfEnabled() {
        if (!plugin.getConfigManager().isUpdatesCheckOnStart()) {
            return;
        }

        updateService.checkAll(results -> {
            int available = 0;
            for (UpdateCandidate candidate : results) {
                if (candidate.status().hasNewerVersion()) {
                    available++;
                    if (Bukkit.getServer() != null) {
                        Plugin matchedPlugin = Bukkit.getPluginManager().getPlugin(candidate.identity().pluginName());
                        if (matchedPlugin != null) {
                            Bukkit.getPluginManager().callEvent(new PluginUpdateFoundEvent(
                                    matchedPlugin, candidate.toUpdateInfo()));
                        }
                    }
                }
            }
            updateService.recordCheckResults(results, available);

            if (available > 0) {
                String mode = plugin.getConfigManager().getUpdatesNotifyMode().toLowerCase(Locale.ROOT);
                if (mode.equals("on-start") || mode.equals("both")) {
                    dispatchNotification(available, results.size());
                }
            }
        });
    }

    private void dispatchNotification(int available, int total) {
        String target = plugin.getConfigManager().getUpdatesNotifyTarget().toLowerCase(Locale.ROOT);
        Map<String, String> placeholders = Map.of(
                "count", String.valueOf(available),
                "available", String.valueOf(available),
                "total", String.valueOf(total)
        );

        if (target.equals("console") || target.equals("all") || target.equals("both")) {
            plugin.getConfigManager().executeActions(Bukkit.getConsoleSender(), "update.plugins-notify-console", placeholders);
        }

        if (target.equals("players") || target.equals("all") || target.equals("both")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (canReceiveNotify(player)) {
                    plugin.getConfigManager().executeActions(player, "update.plugins-notify", placeholders);
                }
            }
        }
    }

    public boolean canReceiveNotify(Player player) {
        return player != null && player.isOnline() && (
                player.hasPermission("plugmanreloaded.admin")
                || player.hasPermission("plugmanreloaded.notify")
                || player.isOp()
        );
    }
}
