package ru.milkyway.plugmanreloaded.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.api.UpdateInfo;

public class PluginUpdateFoundEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final UpdateInfo updateInfo;

    public PluginUpdateFoundEvent(Plugin plugin, UpdateInfo updateInfo) {
        this.plugin = plugin;
        this.updateInfo = updateInfo;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public String getPluginName() {
        return plugin != null ? plugin.getName() : (updateInfo != null ? updateInfo.pluginName() : "Unknown");
    }

    public UpdateInfo getUpdateInfo() {
        return updateInfo;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

