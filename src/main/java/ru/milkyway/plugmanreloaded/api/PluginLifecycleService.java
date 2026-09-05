package ru.milkyway.plugmanreloaded.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public interface PluginLifecycleService {
    @NotNull PluginResult loadPlugin(@NotNull File file);

    @NotNull PluginResult loadPlugin(@NotNull String pluginName);

    @NotNull PluginResult unloadPlugin(@NotNull Plugin plugin, boolean deep);

    @NotNull PluginResult unloadPlugin(@NotNull String pluginName, boolean deep);

    default @NotNull PluginResult unloadPlugin(@NotNull Plugin plugin) {
        return unloadPlugin(plugin, true);
    }

    default @NotNull PluginResult unloadPlugin(@NotNull String pluginName) {
        return unloadPlugin(pluginName, true);
    }

    @NotNull PluginResult reloadPlugin(@NotNull Plugin plugin);

    @NotNull PluginResult reloadPlugin(@NotNull String pluginName);

    @NotNull PluginResult restartPlugin(@NotNull Plugin plugin);

    @NotNull PluginResult restartPlugin(@NotNull String pluginName);

    @NotNull PluginResult enablePlugin(@NotNull Plugin plugin);

    @NotNull PluginResult enablePlugin(@NotNull String pluginName);

    @NotNull PluginResult disablePlugin(@NotNull Plugin plugin);

    @NotNull PluginResult disablePlugin(@NotNull String pluginName);

    @NotNull PluginResult deletePlugin(@NotNull Plugin plugin);

    @NotNull PluginResult deletePlugin(@NotNull String pluginName);

    boolean isLoaded(String pluginName);

    boolean isEnabled(String pluginName);

    boolean isProtected(String pluginName);
}
