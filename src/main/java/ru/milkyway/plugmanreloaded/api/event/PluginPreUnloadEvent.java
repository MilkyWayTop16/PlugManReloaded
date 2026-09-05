package ru.milkyway.plugmanreloaded.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

public class PluginPreUnloadEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final boolean deep;
    private boolean cancelled = false;

    public PluginPreUnloadEvent(Plugin plugin, boolean deep) {
        this.plugin = plugin;
        this.deep = deep;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public String getPluginName() {
        return plugin != null ? plugin.getName() : "Unknown";
    }

    public boolean isDeep() {
        return deep;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

