package ru.milkyway.plugmanreloaded.download.models;

import java.util.Collections;
import java.util.List;

public record DependencyTree(
        String targetPluginName,
        SearchResultEntry targetEntry,
        List<SearchResultEntry> requiredDependencies,
        List<SearchResultEntry> optionalDependencies,
        List<String> alreadySatisfied,
        List<String> existingDisabledToEnable,
        List<String> existingUnloadedToLoad,
        List<String> unresolvableDependencies,
        boolean hasCycles,
        String cycleDetails
) {
    public DependencyTree {
        if (requiredDependencies == null) requiredDependencies = Collections.emptyList();
        if (optionalDependencies == null) optionalDependencies = Collections.emptyList();
        if (alreadySatisfied == null) alreadySatisfied = Collections.emptyList();
        if (existingDisabledToEnable == null) existingDisabledToEnable = Collections.emptyList();
        if (existingUnloadedToLoad == null) existingUnloadedToLoad = Collections.emptyList();
        if (unresolvableDependencies == null) unresolvableDependencies = Collections.emptyList();
    }

    public boolean hasMissing() {
        return !requiredDependencies.isEmpty() || !existingDisabledToEnable.isEmpty() || !existingUnloadedToLoad.isEmpty();
    }

    public boolean isFullyResolvable() {
        return !hasCycles && unresolvableDependencies.isEmpty();
    }
}

