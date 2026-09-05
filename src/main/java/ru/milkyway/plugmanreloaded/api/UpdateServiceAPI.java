package ru.milkyway.plugmanreloaded.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface UpdateServiceAPI {
    @NotNull CompletableFuture<Optional<UpdateInfo>> checkUpdate(@NotNull Plugin plugin);

    @NotNull CompletableFuture<Optional<UpdateInfo>> checkUpdate(@NotNull String pluginName);

    @NotNull CompletableFuture<List<UpdateInfo>> checkAllUpdates();
}
