package ru.milkyway.plugmanreloaded.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface DependencyGraphService {
    @Nullable DependencyNode getNode(@NotNull String pluginName);

    @NotNull Set<String> getDependents(@NotNull String pluginName);

    @NotNull Set<String> getDependencies(@NotNull String pluginName);

    @NotNull List<String> getCascadeUnloadOrder(@NotNull String pluginName);

    @NotNull List<String> getCascadeLoadOrder(@NotNull String pluginName);

    @NotNull Map<String, DependencyNode> getFullGraph();
}
