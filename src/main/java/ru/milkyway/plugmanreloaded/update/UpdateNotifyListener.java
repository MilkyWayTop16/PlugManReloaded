package ru.milkyway.plugmanreloaded.update;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.Locale;
import java.util.Map;

public final class UpdateNotifyListener implements Listener {

    private final PlugManReloaded plugin;
    private final UpdateService updateService;

    public UpdateNotifyListener(PlugManReloaded plugin, UpdateService updateService) {
        this.plugin = plugin;
        this.updateService = updateService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isUpdatesCheckOnStart()) return;

        String mode = plugin.getConfigManager().getUpdatesNotifyMode().toLowerCase(Locale.ROOT);
        if (!mode.equals("on-join") && !mode.equals("both")) return;

        String target = plugin.getConfigManager().getUpdatesNotifyTarget().toLowerCase(Locale.ROOT);
        if (target.equals("console")) return;

        if (!updateService.isInitialChecked() || updateService.getLastAvailableCount() <= 0) return;

        Player player = event.getPlayer();
        if (!updateService.getNotifications().canReceiveNotify(player)) return;

        TaskScheduler.runSyncLater(plugin, () -> {
            if (player.isOnline()) {
                Map<String, String> placeholders = Map.of(
                        "count", String.valueOf(updateService.getLastAvailableCount()),
                        "available", String.valueOf(updateService.getLastAvailableCount()),
                        "total", String.valueOf(updateService.getLastTotalCount())
                );
                plugin.getConfigManager().executeActions(player, "update.plugins-notify", placeholders);
            }
        }, 40L);
    }
}
