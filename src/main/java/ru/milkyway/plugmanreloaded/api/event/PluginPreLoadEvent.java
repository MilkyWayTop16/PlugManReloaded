package ru.milkyway.plugmanreloaded.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.io.File;

public class PluginPreLoadEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String pluginName;
    private final File jarFile;
    private boolean cancelled = false;

    public PluginPreLoadEvent(String pluginName, File jarFile) {
        this.pluginName = pluginName;
        this.jarFile = jarFile;
    }

    public String getPluginName() {
        return pluginName;
    }

    public File getJarFile() {
        return jarFile;
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

