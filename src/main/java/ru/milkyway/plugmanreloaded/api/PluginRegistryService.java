package ru.milkyway.plugmanreloaded.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;
import java.util.Optional;

public interface PluginRegistryService {
    @NotNull Optional<PluginInfo> getPluginInfo(@NotNull String pluginName);

    @NotNull Optional<PluginInfo> getPluginInfo(@NotNull Plugin plugin);

    @NotNull Optional<File> findJarFile(@NotNull String pluginName);

    @NotNull List<String> getLoadablePluginNames();

    @NotNull List<PluginInfo> getAllPlugins();
}
