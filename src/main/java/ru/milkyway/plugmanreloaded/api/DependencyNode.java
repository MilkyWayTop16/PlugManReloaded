package ru.milkyway.plugmanreloaded.api;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DependencyNode {
    private final String pluginName;
    private final Set<String> hardDependencies = new HashSet<>();
    private final Set<String> softDependencies = new HashSet<>();
    private final Set<String> dependents = new HashSet<>();

    public DependencyNode(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getName() {
        return pluginName;
    }

    public Set<String> getHardDependencies() {
        return Collections.unmodifiableSet(hardDependencies);
    }

    public Set<String> getSoftDependencies() {
        return Collections.unmodifiableSet(softDependencies);
    }

    public Set<String> getDependents() {
        return Collections.unmodifiableSet(dependents);
    }

    public void addHardDependency(String dep) {
        if (dep != null && !dep.isBlank()) {
            this.hardDependencies.add(dep);
        }
    }

    public void addSoftDependency(String dep) {
        if (dep != null && !dep.isBlank()) {
            this.softDependencies.add(dep);
        }
    }

    public void addDependent(@Nullable String dependent) {
        if (dependent == null || dependent.isBlank()) {
            return;
        }
        if (dependent.equalsIgnoreCase(this.pluginName)) {
            return;
        }

        this.dependents.add(dependent);
    }
}

