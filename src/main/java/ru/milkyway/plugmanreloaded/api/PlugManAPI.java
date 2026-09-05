package ru.milkyway.plugmanreloaded.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface PlugManAPI {

    static PlugManAPI get() {
        return PlugManProvider.get();
    }

    default PlugManAPI lifecycle() {
        return this;
    }

    default PlugManAPI graph() {
        return this;
    }

    default PlugManAPI registry() {
        return this;
    }

    default PlugManAPI updates() {
        return this;
    }

    default PlugManAPI hotswap() {
        return this;
    }

    @NotNull PluginResult loadPlugin(@NotNull File file);

    @NotNull PluginResult loadPlugin(@NotNull String pluginName);

    default @NotNull PluginResult unloadPlugin(@NotNull Plugin plugin) {
        return unloadPlugin(plugin, true);
    }

    @NotNull PluginResult unloadPlugin(@NotNull Plugin plugin, boolean deep);

    default @NotNull PluginResult unloadPlugin(@NotNull String pluginName) {
        return unloadPlugin(pluginName, true);
    }

    @NotNull PluginResult unloadPlugin(@NotNull String pluginName, boolean deep);

    @NotNull PluginResult reloadPlugin(@NotNull Plugin plugin);

    @NotNull PluginResult reloadPlugin(@NotNull String pluginName);

    @NotNull PluginResult restartPlugin(@NotNull Plugin plugin);

    @NotNull PluginResult restartPlugin(@NotNull String pluginName);

    @NotNull PluginResult enablePlugin(@NotNull Plugin plugin);

    @NotNull PluginResult enablePlugin(@NotNull String pluginName);

    @NotNull PluginResult disablePlugin(@NotNull Plugin plugin);

    @NotNull PluginResult disablePlugin(@NotNull String pluginName);

    @NotNull PluginResult deletePlugin(@Nullable Plugin plugin);

    @NotNull PluginResult deletePlugin(@NotNull String pluginName);

    boolean isPluginLoaded(@NotNull String pluginName);

    boolean isPluginEnabled(@NotNull String pluginName);

    boolean isPluginProtected(@NotNull String pluginName);

    @Nullable DependencyNode getNode(@NotNull String pluginName);

    @NotNull Set<String> getDependents(@NotNull String pluginName);

    @NotNull Set<String> getDependencies(@NotNull String pluginName);

    @NotNull List<String> getCascadeUnloadOrder(@NotNull String pluginName);

    @NotNull List<String> getCascadeLoadOrder(@NotNull String pluginName);

    @NotNull Map<String, DependencyNode> getFullGraph();

    @NotNull Optional<PluginInfo> getPluginInfo(@NotNull String pluginName);

    @NotNull Optional<PluginInfo> getPluginInfo(@NotNull Plugin plugin);

    @NotNull Optional<File> findJarFile(@NotNull String pluginName);

    @NotNull List<String> getLoadablePluginNames();

    @NotNull List<PluginInfo> getAllPlugins();

    @NotNull CompletableFuture<Optional<UpdateInfo>> checkUpdate(@Nullable Plugin plugin);

    @NotNull CompletableFuture<Optional<UpdateInfo>> checkUpdate(@NotNull String pluginName);

    @NotNull CompletableFuture<List<UpdateInfo>> checkAllUpdates();

    boolean isHotSwapEnabled();

    void setHotSwapEnabled(boolean enabled);

    default boolean isEnabled() {
        return isHotSwapEnabled();
    }

    default void setEnabled(boolean enabled) {
        setHotSwapEnabled(enabled);
    }

    static @NotNull PluginResult load(@NotNull File file) {
        return get().loadPlugin(file);
    }

    static @NotNull PluginResult load(@NotNull String pluginName) {
        return get().loadPlugin(pluginName);
    }

    static @NotNull PluginResult unload(@NotNull Plugin plugin) {
        return get().unloadPlugin(plugin);
    }

    static @NotNull PluginResult unload(@NotNull String pluginName) {
        return get().unloadPlugin(pluginName);
    }

    static @NotNull PluginResult reload(@NotNull Plugin plugin) {
        return get().reloadPlugin(plugin);
    }

    static @NotNull PluginResult reload(@NotNull String pluginName) {
        return get().reloadPlugin(pluginName);
    }

    static @NotNull PluginResult restart(@NotNull Plugin plugin) {
        return get().restartPlugin(plugin);
    }

    static @NotNull PluginResult restart(@NotNull String pluginName) {
        return get().restartPlugin(pluginName);
    }

    static @NotNull PluginResult enable(@NotNull Plugin plugin) {
        return get().enablePlugin(plugin);
    }

    static @NotNull PluginResult enable(@NotNull String pluginName) {
        return get().enablePlugin(pluginName);
    }

    static @NotNull PluginResult disable(@NotNull Plugin plugin) {
        return get().disablePlugin(plugin);
    }

    static @NotNull PluginResult disable(@NotNull String pluginName) {
        return get().disablePlugin(pluginName);
    }

    static @NotNull PluginResult delete(@Nullable Plugin plugin) {
        return get().deletePlugin(plugin);
    }

    static @NotNull PluginResult delete(@NotNull String pluginName) {
        return get().deletePlugin(pluginName);
    }

    static @NotNull Optional<PluginInfo> getInfo(@NotNull String pluginName) {
        return get().getPluginInfo(pluginName);
    }

    static @NotNull Optional<PluginInfo> getInfo(@NotNull Plugin plugin) {
        return get().getPluginInfo(plugin);
    }

    static boolean isLoaded(String pluginName) {
        return get().isPluginLoaded(pluginName);
    }

    static boolean isEnabled(String pluginName) {
        return get().isPluginEnabled(pluginName);
    }

    static boolean isProtected(String pluginName) {
        return get().isPluginProtected(pluginName);
    }
}
