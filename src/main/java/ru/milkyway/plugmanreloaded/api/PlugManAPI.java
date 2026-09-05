package ru.milkyway.plugmanreloaded.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Optional;

public interface PlugManAPI {
    static PlugManAPI get() {
        return PlugManProvider.get();
    }

    PluginLifecycleService lifecycle();

    DependencyGraphService graph();

    PluginRegistryService registry();

    UpdateServiceAPI updates();

    HotSwapService hotswap();

    static @NotNull PluginResult load(@NotNull File file) {
        return get().lifecycle().loadPlugin(file);
    }

    static @NotNull PluginResult load(@NotNull String pluginName) {
        return get().lifecycle().loadPlugin(pluginName);
    }

    static @NotNull PluginResult unload(@NotNull Plugin plugin) {
        return get().lifecycle().unloadPlugin(plugin);
    }

    static @NotNull PluginResult unload(@NotNull String pluginName) {
        return get().lifecycle().unloadPlugin(pluginName);
    }

    static @NotNull PluginResult reload(@NotNull Plugin plugin) {
        return get().lifecycle().reloadPlugin(plugin);
    }

    static @NotNull PluginResult reload(@NotNull String pluginName) {
        return get().lifecycle().reloadPlugin(pluginName);
    }

    static @NotNull PluginResult restart(@NotNull Plugin plugin) {
        return get().lifecycle().restartPlugin(plugin);
    }

    static @NotNull PluginResult restart(@NotNull String pluginName) {
        return get().lifecycle().restartPlugin(pluginName);
    }

    static @NotNull PluginResult enable(@NotNull Plugin plugin) {
        return get().lifecycle().enablePlugin(plugin);
    }

    static @NotNull PluginResult enable(@NotNull String pluginName) {
        return get().lifecycle().enablePlugin(pluginName);
    }

    static @NotNull PluginResult disable(@NotNull Plugin plugin) {
        return get().lifecycle().disablePlugin(plugin);
    }

    static @NotNull PluginResult disable(@NotNull String pluginName) {
        return get().lifecycle().disablePlugin(pluginName);
    }

    static @NotNull PluginResult delete(@NotNull Plugin plugin) {
        return get().lifecycle().deletePlugin(plugin);
    }

    static @NotNull PluginResult delete(@NotNull String pluginName) {
        return get().lifecycle().deletePlugin(pluginName);
    }

    static @NotNull Optional<PluginInfo> getInfo(@NotNull String pluginName) {
        return get().registry().getPluginInfo(pluginName);
    }

    static @NotNull Optional<PluginInfo> getInfo(@NotNull Plugin plugin) {
        return get().registry().getPluginInfo(plugin);
    }

    static boolean isLoaded(String pluginName) {
        return get().lifecycle().isLoaded(pluginName);
    }

    static boolean isEnabled(String pluginName) {
        return get().lifecycle().isEnabled(pluginName);
    }

    static boolean isProtected(String pluginName) {
        return get().lifecycle().isProtected(pluginName);
    }
}
