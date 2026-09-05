package ru.milkyway.plugmanreloaded.update.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

public class ManualSourceListener implements Listener {

    private final PlugManReloaded plugin;
    private final ManualSources manualSources;

    public ManualSourceListener(PlugManReloaded plugin, ManualSources manualSources) {
        this.plugin = plugin;
        this.manualSources = manualSources;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ManualSourceSession session = manualSources.get(player);
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        try {
            event.viewers().clear();
        } catch (Exception t) {
            Log.debug("manualsourcelistener.hide-message-failed", t);
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        if (session.tryConsumeInput()) {
            TaskScheduler.runSync(plugin, () -> manualSources.processInput(player, text));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBukkitChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ManualSourceSession session = manualSources.get(player);
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        try {
            event.getRecipients().clear();
        } catch (Exception t) {
            Log.debug("manualsourcelistener.hide-message-failed", t);
        }
        String text = event.getMessage() != null ? event.getMessage().trim() : "";

        if (session.tryConsumeInput()) {
            TaskScheduler.runSync(plugin, () -> manualSources.processInput(player, text));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            manualSources.removeSession(event.getPlayer().getUniqueId());
        }
    }
}

