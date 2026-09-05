package ru.milkyway.plugmanreloaded.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PluginUnloadedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String pluginName;
    private final boolean deep;

    public PluginUnloadedEvent(String pluginName, boolean deep) {
        this.pluginName = pluginName;
        this.deep = deep;
    }

    public String getPluginName() {
        return pluginName;
    }

    public boolean isDeep() {
        return deep;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

