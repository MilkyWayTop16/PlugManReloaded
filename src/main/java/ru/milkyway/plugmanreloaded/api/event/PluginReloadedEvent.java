package ru.milkyway.plugmanreloaded.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

public class PluginReloadedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final long elapsedMs;

    public PluginReloadedEvent(Plugin plugin, long elapsedMs) {
        this.plugin = plugin;
        this.elapsedMs = elapsedMs;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public String getPluginName() {
        return plugin != null ? plugin.getName() : "Unknown";
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

