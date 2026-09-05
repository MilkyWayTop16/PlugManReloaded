package ru.milkyway.plugmanreloaded.bridge;

import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.api.PluginResult;

import java.io.File;

public interface PlatformBridge {

    PluginResult enablePlugin(Plugin plugin);

    PluginResult disablePlugin(Plugin plugin);

    PluginResult loadPlugin(File file);

    PluginResult unloadPlugin(Plugin plugin);

    PluginResult reloadPlugin(Plugin plugin);

    PluginResult restartPlugin(Plugin plugin);

    void syncCommands();

    boolean isPaperPlugin(File file);

    File getPluginFile(Plugin plugin);
}

