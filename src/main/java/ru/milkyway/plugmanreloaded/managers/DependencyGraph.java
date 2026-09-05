package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.DependencyNode;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;

import java.util.*;
import java.util.function.Function;

public class DependencyGraph {

    private final PlugManReloaded plugin;

    public DependencyGraph(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    public Map<String, DependencyNode> buildGraph() {
        return buildGraph(true);
    }

    public Map<String, DependencyNode> buildGraph(boolean includeSoftDepends) {
        Map<String, DependencyNode> graph = new HashMap<>();
        Map<String, List<DependencyNode>> providesMap = new HashMap<>();

        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p == null) continue;
            DependencyNode node = new DependencyNode(p.getName());
            graph.put(p.getName().toLowerCase(Locale.ROOT), node);

            PluginDescriptionFile desc = p.getDescription();
            if (desc != null && desc.getProvides() != null) {
                for (String prov : desc.getProvides()) {
                    if (prov != null && !prov.isBlank()) {
                        registerProvider(providesMap, prov, node);
                    }
                }
            }

            readPaperMetaProvides(p, node, providesMap);
        }

        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p == null) continue;
            String nameLower = p.getName().toLowerCase(Locale.ROOT);
            DependencyNode node = graph.get(nameLower);
            if (node == null) continue;

            PluginDescriptionFile desc = p.getDescription();

            if (desc != null) {
                for (String dep : desc.getDepend()) {
                    if (dep == null || dep.isBlank()) continue;
                    node.addHardDependency(dep);
                    for (DependencyNode depNode : resolveNodes(graph, providesMap, dep)) {
                        depNode.addDependent(p.getName());
                    }
                }
            }

            if (includeSoftDepends && desc != null) {
                for (String sdep : desc.getSoftDepend()) {
                    if (sdep == null || sdep.isBlank()) continue;
                    node.addSoftDependency(sdep);
                    for (DependencyNode sdepNode : resolveNodes(graph, providesMap, sdep)) {
                        sdepNode.addDependent(p.getName());
                    }
                }
            }

            if (includeSoftDepends && desc != null && desc.getLoadBefore() != null) {
                for (String before : desc.getLoadBefore()) {
                    if (before == null || before.isBlank()) continue;
                    node.addDependent(before);
                    for (DependencyNode beforeNode : resolveNodes(graph, providesMap, before)) {
                        beforeNode.addSoftDependency(p.getName());
                    }
                }
            }

            readPaperMetaDependencies(p, node, graph, providesMap, includeSoftDepends);
        }

        return graph;
    }

    private void registerProvider(@Nullable Map<String, List<DependencyNode>> providesMap, String alias, DependencyNode node) {
        if (providesMap == null || alias == null || alias.isBlank() || node == null) return;
        List<DependencyNode> list = providesMap.computeIfAbsent(alias.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
        if (!list.contains(node)) {
            list.add(node);
        }
    }

    private List<DependencyNode> resolveNodes(Map<String, DependencyNode> graph,
                                              Map<String, List<DependencyNode>> providesMap, @Nullable String name) {
        if (name == null || name.isBlank()) return Collections.emptyList();
        String lower = name.toLowerCase(Locale.ROOT);
        DependencyNode direct = graph.get(lower);
        if (direct != null) return List.of(direct);
        if (providesMap != null) {
            List<DependencyNode> providers = providesMap.get(lower);
            if (providers != null && !providers.isEmpty()) return providers;
        }
        DependencyNode synthetic = graph.computeIfAbsent(lower, k -> new DependencyNode(name));
        return List.of(synthetic);
    }

    private void readPaperMetaProvides(Plugin p, DependencyNode node, Map<String, List<DependencyNode>> providesMap) {
        try {
            Object meta = ReflectionHelper.invokeMethod(p, "getPluginMeta");
            if (meta == null) return;
            Object provided = ReflectionHelper.invokeMethod(meta, "getProvidedPlugins");
            if (provided instanceof Iterable<?> iterable) {
                for (Object o : iterable) {
                    String prov = extractDescriptorName(o);
                    if (prov != null && !prov.isBlank()) {
                        registerProvider(providesMap, prov, node);
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("dependencygraph.provides-reflection-failed", t);
        }
    }

    private void readPaperMetaDependencies(Plugin p, DependencyNode node, Map<String, DependencyNode> graph,
                                           Map<String, List<DependencyNode>> providesMap, boolean includeSoftDepends) {
        try {
            Object meta = ReflectionHelper.invokeMethod(p, "getPluginMeta");
            if (meta == null) return;

            Object hardDeps = ReflectionHelper.invokeMethod(meta, "getPluginDependencies");
            if (hardDeps instanceof Iterable<?> iterable) {
                for (Object o : iterable) {
                    String depName = extractDescriptorName(o);
                    if (depName != null && !depName.isBlank()) {
                        node.addHardDependency(depName);
                        for (DependencyNode depNode : resolveNodes(graph, providesMap, depName)) {
                            depNode.addDependent(p.getName());
                        }
                    }
                }
            }

            if (includeSoftDepends) {
                Object softDeps = ReflectionHelper.invokeMethod(meta, "getPluginSoftDependencies");
                if (softDeps instanceof Iterable<?> iterable) {
                    for (Object o : iterable) {
                        String depName = extractDescriptorName(o);
                        if (depName != null && !depName.isBlank()) {
                            node.addSoftDependency(depName);
                            for (DependencyNode depNode : resolveNodes(graph, providesMap, depName)) {
                                depNode.addDependent(p.getName());
                            }
                        }
                    }
                }

                Object loadBefore = ReflectionHelper.invokeMethod(meta, "getLoadBeforePlugins");
                if (loadBefore instanceof Iterable<?> iterable) {
                    for (Object o : iterable) {
                        String beforeName = extractDescriptorName(o);
                        if (beforeName != null && !beforeName.isBlank()) {
                            node.addDependent(beforeName);
                            for (DependencyNode beforeNode : resolveNodes(graph, providesMap, beforeName)) {
                                beforeNode.addSoftDependency(p.getName());
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("dependencygraph.dependencies-reflection-failed", t);
        }
    }

    private @Nullable String extractDescriptorName(@Nullable Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            Object name = ReflectionHelper.invokeMethod(obj, "getName");
            if (name instanceof String s) return s;
        } catch (Throwable t) {
            Log.debug("dependencygraph.getname-unavailable", t);
        }
        return obj.toString();
    }

    public Set<String> getDependents(String pluginName) {
        return getDependents(pluginName, true);
    }

    public Set<String> getDependents(@Nullable String pluginName, boolean includeSoftDepends) {
        if (pluginName == null) return Collections.emptySet();
        return getDependents(pluginName, buildGraph(includeSoftDepends));
    }

    public Set<String> getDependents(@Nullable String pluginName, Map<String, DependencyNode> graph) {
        if (pluginName == null || graph == null) return Collections.emptySet();
        DependencyNode node = graph.get(pluginName.toLowerCase(Locale.ROOT));
        if (node == null) return Collections.emptySet();

        Set<String> allDependents = new HashSet<>();
        collectAllDependents(node, graph, allDependents);
        return allDependents;
    }

    private void collectAllDependents(DependencyNode current, Map<String, DependencyNode> graph, Set<String> visited) {
        for (String depName : current.getDependents()) {
            if (visited.add(depName)) {
                DependencyNode next = graph.get(depName.toLowerCase(Locale.ROOT));
                if (next != null) {
                    collectAllDependents(next, graph, visited);
                }
            }
        }
    }

    public static Set<String> resolveDependentsWithFallback(@Nullable Set<String> directDependents,
                                                              DependencyGraph graphManager,
                                                              String pluginName) {
        if (directDependents == null) directDependents = Collections.emptySet();
        if (!directDependents.isEmpty() || graphManager == null) {
            return directDependents;
        }
        return graphManager.getDependents(pluginName, true);
    }

    public List<String> calculateCascadeOrder(String rootPluginName) {
        return calculateCascadeOrder(rootPluginName, true);
    }

    public List<String> calculateCascadeOrder(String rootPluginName, boolean includeSoftDepends) {
        Map<String, DependencyNode> fullGraph = buildGraph(includeSoftDepends);

        DependencyNode rootNode = fullGraph.get(rootPluginName.toLowerCase(Locale.ROOT));
        Set<String> affectedPlugins = new HashSet<>();
        affectedPlugins.add(rootPluginName);

        if (rootNode != null) {
            collectAllDependents(rootNode, fullGraph, affectedPlugins);
        }

        Set<String> affectedLower = new HashSet<>();
        for (String p : affectedPlugins) {
            affectedLower.add(p.toLowerCase(Locale.ROOT));
        }

        Map<String, Set<String>> inDegree = new HashMap<>();
        Map<String, Set<String>> adj = new HashMap<>();

        for (String pLower : affectedLower) {
            inDegree.put(pLower, new HashSet<>());
            adj.put(pLower, new HashSet<>());
        }

        for (String pLower : affectedLower) {
            DependencyNode node = fullGraph.get(pLower);
            if (node != null) {
                for (String dep : node.getHardDependencies()) {
                    String depLower = dep.toLowerCase(Locale.ROOT);
                    if (affectedLower.contains(depLower)) {
                        inDegree.get(pLower).add(depLower);
                        adj.get(depLower).add(pLower);
                    }
                }
                if (includeSoftDepends) {
                    for (String sdep : node.getSoftDependencies()) {
                        String sdepLower = sdep.toLowerCase(Locale.ROOT);
                        if (affectedLower.contains(sdepLower)) {
                            inDegree.get(pLower).add(sdepLower);
                            adj.get(sdepLower).add(pLower);
                        }
                    }
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Set<String>> entry : inDegree.entrySet()) {
            if (entry.getValue().isEmpty()) {
                queue.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            DependencyNode node = fullGraph.get(current);
            order.add(node != null ? node.getName() : current);

            Set<String> dependents = adj.get(current);
            if (dependents != null) {
                for (String dependent : dependents) {
                    Set<String> depsOfDependent = inDegree.get(dependent);
                    if (depsOfDependent != null) {
                        depsOfDependent.remove(current);
                        if (depsOfDependent.isEmpty()) {
                            queue.add(dependent);
                        }
                    }
                }
            }
        }

        List<String> leftover = new ArrayList<>();
        for (String p : affectedPlugins) {
            boolean found = false;
            for (String added : order) {
                if (added.equalsIgnoreCase(p)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                leftover.add(p);
            }
        }
        leftover.sort(String.CASE_INSENSITIVE_ORDER);
        order.addAll(leftover);

        return order;
    }
    public List<Plugin> sortPluginsTopologically(@Nullable Collection<Plugin> plugins) {
        return sortByDependencies(plugins, Plugin::getName);
    }

    public List<String> sortNamesTopologically(@Nullable Collection<String> pluginNames) {
        return sortByDependencies(pluginNames, name -> name);
    }

    private <T> List<T> sortByDependencies(@Nullable Collection<T> items, Function<T, String> nameOf) {
        if (items == null || items.isEmpty()) return Collections.emptyList();

        Map<String, DependencyNode> fullGraph = buildGraph(true);
        Map<String, T> byKey = new LinkedHashMap<>();
        for (T item : items) {
            String name = item != null ? nameOf.apply(item) : null;
            if (name != null && !name.isBlank()) {
                byKey.put(name.toLowerCase(Locale.ROOT), item);
            }
        }

        Set<String> affected = byKey.keySet();
        Map<String, Set<String>> waitingFor = new HashMap<>();
        Map<String, Set<String>> unblocks = new HashMap<>();
        for (String key : affected) {
            waitingFor.put(key, new HashSet<>());
            unblocks.put(key, new HashSet<>());
        }

        for (String key : affected) {
            DependencyNode node = fullGraph.get(key);
            if (node == null) continue;

            for (String dependency : node.getHardDependencies()) {
                linkWithin(affected, key, dependency, waitingFor, unblocks);
            }
            for (String dependency : node.getSoftDependencies()) {
                linkWithin(affected, key, dependency, waitingFor, unblocks);
            }
        }

        List<T> sorted = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Queue<String> ready = new ArrayDeque<>();
        waitingFor.forEach((key, waits) -> {
            if (waits.isEmpty()) {
                ready.add(key);
            }
        });

        while (!ready.isEmpty()) {
            String current = ready.poll();
            T item = byKey.get(current);
            if (item != null && added.add(current)) {
                sorted.add(item);
            }

            for (String dependent : unblocks.getOrDefault(current, Set.of())) {
                Set<String> waits = waitingFor.get(dependent);
                if (waits != null) {
                    waits.remove(current);
                    if (waits.isEmpty()) {
                        ready.add(dependent);
                    }
                }
            }
        }

        for (T item : items) {
            String name = item != null ? nameOf.apply(item) : null;
            if (name == null || name.isBlank()) continue;

            String key = name.toLowerCase(Locale.ROOT);
            if (added.add(key)) {
                sorted.add(byKey.getOrDefault(key, item));
            }
        }
        return sorted;
    }

    private static void linkWithin(Set<String> affected, String owner, String dependency,
                                   Map<String, Set<String>> waitingFor, Map<String, Set<String>> unblocks) {
        String key = dependency.toLowerCase(Locale.ROOT);
        if (affected.contains(key)) {
            waitingFor.get(owner).add(key);
            unblocks.get(key).add(owner);
        }
    }

    private record JarGraph(Map<String, PluginJarIndex.JarInfo> byKey,
                            Map<String, List<String>> provides,
                            Map<String, List<String>> hardDeps,
                            Map<String, List<String>> softDeps) {}

    public List<PluginJarIndex.JarInfo> sortUnloadedJarsTopologically(@Nullable Collection<PluginJarIndex.JarInfo> jars) {
        if (jars == null || jars.isEmpty()) return Collections.emptyList();

        JarGraph graph = readDescriptors(jars);
        Set<String> known = graph.byKey().keySet();

        Map<String, Set<String>> waitingFor = new HashMap<>();
        Map<String, Set<String>> unblocks = new HashMap<>();
        for (String name : known) {
            waitingFor.put(name, new HashSet<>());
            unblocks.put(name, new HashSet<>());
        }

        for (String name : known) {
            link(name, graph.hardDeps().getOrDefault(name, List.of()), graph, known, waitingFor, unblocks);
            link(name, graph.softDeps().getOrDefault(name, List.of()), graph, known, waitingFor, unblocks);
        }

        List<PluginJarIndex.JarInfo> sorted = drain(graph.byKey(), waitingFor, unblocks);
        appendUnsorted(jars, sorted);
        return sorted;
    }

    private static JarGraph readDescriptors(Collection<PluginJarIndex.JarInfo> jars) {
        Map<String, PluginJarIndex.JarInfo> byKey = new LinkedHashMap<>();
        Map<String, List<String>> provides = new HashMap<>();
        Map<String, List<String>> hardDeps = new HashMap<>();
        Map<String, List<String>> softDeps = new HashMap<>();

        for (PluginJarIndex.JarInfo info : jars) {
            if (info == null || info.file() == null) continue;

            String key = info.preferredName().toLowerCase(Locale.ROOT);
            byKey.put(key, info);

            PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(info.file());
            if (desc == null) continue;

            for (String provided : desc.provides()) {
                if (provided != null && !provided.isBlank()) {
                    provides.computeIfAbsent(provided.toLowerCase(Locale.ROOT), unused -> new ArrayList<>()).add(key);
                }
            }
            hardDeps.put(key, desc.depend());
            softDeps.put(key, desc.softDepend());
        }
        return new JarGraph(byKey, provides, hardDeps, softDeps);
    }

    private static void link(String owner, List<String> dependencies, JarGraph graph, Set<String> known,
                             Map<String, Set<String>> waitingFor, Map<String, Set<String>> unblocks) {
        for (String dependency : dependencies) {
            if (dependency == null || dependency.isBlank()) continue;

            String key = dependency.toLowerCase(Locale.ROOT);
            if (known.contains(key)) {
                waitingFor.get(owner).add(key);
                unblocks.get(key).add(owner);
                continue;
            }
            for (String provider : graph.provides().getOrDefault(key, List.of())) {
                if (known.contains(provider)) {
                    waitingFor.get(owner).add(provider);
                    unblocks.get(provider).add(owner);
                }
            }
        }
    }

    private static List<PluginJarIndex.JarInfo> drain(Map<String, PluginJarIndex.JarInfo> byKey,
                                                      Map<String, Set<String>> waitingFor,
                                                      Map<String, Set<String>> unblocks) {
        Queue<String> ready = new ArrayDeque<>();
        waitingFor.forEach((name, waits) -> {
            if (waits.isEmpty()) {
                ready.add(name);
            }
        });

        List<PluginJarIndex.JarInfo> sorted = new ArrayList<>();
        Set<String> added = new HashSet<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            PluginJarIndex.JarInfo info = byKey.get(current);
            if (info != null && added.add(current)) {
                sorted.add(info);
            }

            for (String dependent : unblocks.getOrDefault(current, Set.of())) {
                Set<String> waits = waitingFor.get(dependent);
                if (waits != null) {
                    waits.remove(current);
                    if (waits.isEmpty()) {
                        ready.add(dependent);
                    }
                }
            }
        }
        return sorted;
    }

    private static void appendUnsorted(Collection<PluginJarIndex.JarInfo> jars, List<PluginJarIndex.JarInfo> sorted) {
        Set<String> present = new HashSet<>();
        for (PluginJarIndex.JarInfo info : sorted) {
            present.add(info.preferredName().toLowerCase(Locale.ROOT));
        }
        for (PluginJarIndex.JarInfo info : jars) {
            if (info != null && present.add(info.preferredName().toLowerCase(Locale.ROOT))) {
                sorted.add(info);
            }
        }
    }
}

