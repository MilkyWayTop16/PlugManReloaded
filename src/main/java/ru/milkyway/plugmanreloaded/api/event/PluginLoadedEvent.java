package ru.milkyway.plugmanreloaded.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.io.File;

public class PluginLoadedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final File jarFile;

    public PluginLoadedEvent(Plugin plugin, File jarFile) {
        this.plugin = plugin;
        this.jarFile = jarFile;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public String getPluginName() {
        return plugin != null ? plugin.getName() : (jarFile != null ? jarFile.getName() : "Unknown");
    }

    public File getJarFile() {
        return jarFile;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

