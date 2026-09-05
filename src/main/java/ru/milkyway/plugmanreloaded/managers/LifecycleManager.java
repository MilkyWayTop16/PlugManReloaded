package ru.milkyway.plugmanreloaded.managers;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.DependencyNode;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PluginInfo;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.api.event.PluginLoadedEvent;
import ru.milkyway.plugmanreloaded.api.event.PluginPreLoadEvent;
import ru.milkyway.plugmanreloaded.api.event.PluginPreReloadEvent;
import ru.milkyway.plugmanreloaded.api.event.PluginPreUnloadEvent;
import ru.milkyway.plugmanreloaded.api.event.PluginReloadedEvent;
import ru.milkyway.plugmanreloaded.api.event.PluginUnloadedEvent;
import ru.milkyway.plugmanreloaded.bridge.FoliaBridge;
import ru.milkyway.plugmanreloaded.bridge.LegacyBukkitBridge;
import ru.milkyway.plugmanreloaded.bridge.ModernPaperBridge;
import ru.milkyway.plugmanreloaded.bridge.PlatformBridge;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.utils.ErrorAnalyzer;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.WeakHashMap;

@Getter
public final class LifecycleManager {

    private final PlugManReloaded plugin;
    private final PlatformBridge bridge;
    private final DependencyGraph dependencyGraph;
    private final UnloadSafetyChecker safetyAdvisor;
    private final PluginCleanup pluginCleanup;
    private final BrigadierManager brigadierManager;
    private final PluginJarIndex jarIndex;
    private final Map<Plugin, File> pluginFileCache = Collections.synchronizedMap(new WeakHashMap<>());

    public LifecycleManager(PlugManReloaded plugin) {
        this.plugin = plugin;
        this.brigadierManager = new BrigadierManager(plugin);
        this.pluginCleanup = new PluginCleanup(plugin);
        this.dependencyGraph = new DependencyGraph(plugin);
        this.safetyAdvisor = new UnloadSafetyChecker(plugin);
        this.jarIndex = new PluginJarIndex(plugin);

        if (PlatformDetector.isFolia()) {
            this.bridge = new FoliaBridge(plugin, brigadierManager, pluginCleanup);
        } else if (PlatformDetector.isModernPaper()) {
            this.bridge = new ModernPaperBridge(plugin, brigadierManager, pluginCleanup);
        } else {
            this.bridge = new LegacyBukkitBridge(plugin, brigadierManager, pluginCleanup);
        }
    }

    public @Nullable Plugin getPlugin(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        PluginManager pm = Bukkit.getPluginManager();
        Plugin p = pm.getPlugin(name);
        if (p != null) return p;

        for (Plugin plugin : pm.getPlugins()) {
            if (plugin.getName().equalsIgnoreCase(name)) {
                return plugin;
            }
        }

        String clean = name.toLowerCase(Locale.ROOT);
        if (clean.endsWith(".jar")) {
            clean = clean.substring(0, clean.length() - 4);
        }

        for (Plugin plugin : pm.getPlugins()) {
            File f = getPluginFile(plugin);
            if (f != null) {
                String fName = f.getName().toLowerCase(Locale.ROOT);
                if (fName.equalsIgnoreCase(name) || fName.equalsIgnoreCase(name + ".jar")) {
                    return plugin;
                }
                int dot = fName.lastIndexOf('.');
                String fStrip = dot > 0 ? fName.substring(0, dot) : fName;
                if (fStrip.equalsIgnoreCase(clean)) {
                    return plugin;
                }
            }
        }

        File jar = jarIndex.find(name);
        if (jar != null) {
            PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(jar);
            if (desc != null && desc.declaredName() != null) {
                Plugin byDesc = pm.getPlugin(desc.declaredName());
                if (byDesc != null) return byDesc;
                for (Plugin plugin : pm.getPlugins()) {
                    if (plugin.getName().equalsIgnoreCase(desc.declaredName())) {
                        return plugin;
                    }
                }
            }
        }

        return null;
    }

    public @Nullable File getPluginFile(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return null;
        File cached = pluginFileCache.get(targetPlugin);
        if (cached != null && cached.exists()) {
            return cached;
        }
        File resolved = bridge.getPluginFile(targetPlugin);
        if (resolved != null && resolved.exists()) {
            pluginFileCache.put(targetPlugin, resolved);
        }
        return resolved;
    }

    public void invalidatePluginFile(Plugin plugin) {
        if (plugin != null) {
            pluginFileCache.remove(plugin);
        }
    }

    public PluginResult enable(Plugin targetPlugin) {
        if (targetPlugin.isEnabled()) {
            return PluginResult.ofError(FailureReason.ALREADY_ENABLED, "plugin", targetPlugin.getName());
        }
        return bridge.enablePlugin(targetPlugin);
    }

    public PluginResult disable(Plugin targetPlugin) {
        if (!targetPlugin.isEnabled()) {
            return PluginResult.ofError(FailureReason.ALREADY_DISABLED, "plugin", targetPlugin.getName());
        }
        if (isProtected(targetPlugin)) {
            return PluginResult.ofError(protectedReason(targetPlugin), "plugin", targetPlugin.getName());
        }
        return bridge.disablePlugin(targetPlugin);
    }

    public PluginResult load(@Nullable File file) {
        if (file == null || !file.exists()) {
            return PluginResult.ofError(FailureReason.FILE_NOT_FOUND, "plugin", file != null ? file.getName() : "null", "file", file != null ? file.getName() : "null");
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(file, null, false);
        if (!report.isValid()) {
            return report.toFailure(file);
        }

        if (Bukkit.getServer() != null) {
            PluginPreLoadEvent pre =
                    new PluginPreLoadEvent(report.pluginNameOr(file), file);
            Bukkit.getPluginManager().callEvent(pre);
            if (pre.isCancelled()) {
                return PluginResult.ofError(FailureReason.OPERATION_CANCELLED, "plugin", pre.getPluginName());
            }
        }

        Log.debug("lifecyclemanager.loading-file", "file", file.getName());
        PluginResult res = bridge.loadPlugin(file);
        if (res.success() && Bukkit.getServer() != null) {
            Plugin p = report.declaredName() != null ? Bukkit.getPluginManager().getPlugin(report.declaredName()) : null;
            Bukkit.getPluginManager().callEvent(new PluginLoadedEvent(p, file));
        }
        return res;
    }

    public PluginResult unload(Plugin targetPlugin) {
        return unload(targetPlugin, true);
    }

    public PluginResult unload(Plugin targetPlugin, boolean deep) {
        if (isProtected(targetPlugin)) {
            return PluginResult.ofError(protectedReason(targetPlugin), "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
        }
        if (!deep && targetPlugin != null) {
            Set<String> dependents = dependencyGraph.getDependents(targetPlugin.getName());
            if (!dependents.isEmpty()) {
                return PluginResult.ofError(FailureReason.HAS_DEPENDENTS, "plugin", targetPlugin.getName(), "dependents", String.join(", ", dependents));
            }
        }
        if (targetPlugin != null && Bukkit.getServer() != null) {
            PluginPreUnloadEvent pre =
                    new PluginPreUnloadEvent(targetPlugin, deep);
            Bukkit.getPluginManager().callEvent(pre);
            if (pre.isCancelled()) {
                return PluginResult.ofError(FailureReason.OPERATION_CANCELLED, "plugin", targetPlugin.getName());
            }
        }
        String pName = targetPlugin != null ? targetPlugin.getName() : "null";
        Log.debug("lifecyclemanager.unloading", "plugin", pName);
        PluginResult res = bridge.unloadPlugin(targetPlugin);
        if (res.success()) {
            invalidatePluginFile(targetPlugin);
            if (Bukkit.getServer() != null) {
                Bukkit.getPluginManager().callEvent(new PluginUnloadedEvent(pName, deep));
            }
        }
        return res;
    }

    public PluginResult reload(Plugin targetPlugin) {
        if (isProtected(targetPlugin)) {
            return PluginResult.ofError(protectedReason(targetPlugin), "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
        }
        if (targetPlugin != null && Bukkit.getServer() != null) {
            PluginPreReloadEvent pre =
                    new PluginPreReloadEvent(targetPlugin);
            Bukkit.getPluginManager().callEvent(pre);
            if (pre.isCancelled()) {
                return PluginResult.ofError(FailureReason.OPERATION_CANCELLED, "plugin", targetPlugin.getName());
            }
        }
        Log.debug("lifecyclemanager.reloading", "plugin", targetPlugin != null ? targetPlugin.getName() : "null");

        File file = getPluginFile(targetPlugin);
        if (file != null && file.exists()) {
            JarValidator.PreFlightReport report = JarValidator.validatePreFlight(file, targetPlugin.getName(), true);
            if (!report.isValid()) {
                return report.toFailure(file);
            }
        }

        try {
            PluginResult res = bridge.reloadPlugin(targetPlugin);
            if (res.success() && Bukkit.getServer() != null) {
                Bukkit.getPluginManager().callEvent(new PluginReloadedEvent(targetPlugin, res.elapsedMs()));
            }
            return res;
        } catch (Exception | LinkageError t) {
            ErrorAnalyzer.ErrorDetails details = ErrorAnalyzer.analyze(t, targetPlugin.getName(), file != null ? file.getName() : targetPlugin.getName());
            return PluginResult.ofError(details.reason() == FailureReason.LOAD_FAILED ? FailureReason.RELOAD_FAILED : details.reason(), t, details.placeholders());
        }
    }

    public PluginResult restart(Plugin targetPlugin) {
        if (isProtected(targetPlugin)) {
            return PluginResult.ofError(protectedReason(targetPlugin), "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
        }

        File file = getPluginFile(targetPlugin);
        if (file == null || !file.exists()) {
            return PluginResult.ofError(FailureReason.FILE_NOT_FOUND, "plugin", targetPlugin != null ? targetPlugin.getName() : "null", "file", file != null ? file.getName() : (targetPlugin != null ? targetPlugin.getName() + ".jar" : "unknown.jar"));
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(file, targetPlugin.getName(), true);
        if (!report.isValid()) {
            return report.toFailure(file);
        }

        if (targetPlugin != null && Bukkit.getServer() != null) {
            PluginPreReloadEvent pre =
                    new PluginPreReloadEvent(targetPlugin);
            Bukkit.getPluginManager().callEvent(pre);
            if (pre.isCancelled()) {
                return PluginResult.ofError(FailureReason.OPERATION_CANCELLED, "plugin", targetPlugin.getName());
            }
        }

        PluginResult res = bridge.restartPlugin(targetPlugin);
        if (res.success()) {
            invalidatePluginFile(targetPlugin);
            if (Bukkit.getServer() != null) {
                Bukkit.getPluginManager().callEvent(new PluginReloadedEvent(targetPlugin, res.elapsedMs()));
            }
        }
        return res;
    }

    public PluginResult cascadeReload(Plugin targetPlugin) {
        return executeCascade(targetPlugin, false);
    }

    public PluginResult cascadeRestart(Plugin targetPlugin) {
        return executeCascade(targetPlugin, true);
    }

    private record CascadePlan(List<String> reloadOrder, Map<String, File> files,
                               Map<String, Boolean> wasEnabled, List<String> skipped) {}

    private record UnloadPhase(List<String> unloaded, @Nullable String failedPlugin, @Nullable String failedError) {

        boolean failed() {
            return failedPlugin != null;
        }
    }

    private PluginResult executeCascade(Plugin targetPlugin, boolean isRestart) {
        if (isProtected(targetPlugin)) {
            return PluginResult.ofError(protectedReason(targetPlugin), "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
        }

        File targetFile = getPluginFile(targetPlugin);
        if (targetFile != null && targetFile.exists()) {
            JarValidator.PreFlightReport targetReport = JarValidator.validatePreFlight(targetFile, targetPlugin.getName(), true);
            if (!targetReport.isValid()) {
                return PluginResult.ofError(FailureReason.RELOAD_FAILED, "plugin", targetPlugin.getName(), "error", targetReport.errorMessage());
            }
        }

        long startTime = System.currentTimeMillis();
        CascadePlan plan = planCascade(targetPlugin);
        if (plan.reloadOrder().isEmpty() || plan.skipped().stream().anyMatch(targetPlugin.getName()::equalsIgnoreCase)) {
            return PluginResult.ofError(FailureReason.RELOAD_FAILED, "plugin", targetPlugin.getName(),
                    "error", message("actions.cascade-reload.details.no-jar"));
        }

        UnloadPhase unloaded = unloadAll(plan.reloadOrder());
        if (unloaded.failed()) {
            return PluginResult.ofError(FailureReason.RELOAD_FAILED, "plugin", targetPlugin.getName(),
                    "error", rollback(unloaded, plan));
        }

        List<String> failedPlugins = loadAll(plan);
        jarIndex.invalidate();
        brigadierManager.syncCommands();

        if (!failedPlugins.isEmpty() || !plan.skipped().isEmpty()) {
            return PluginResult.ofError(FailureReason.RELOAD_FAILED, "plugin", targetPlugin.getName(),
                    "error", describeProblems(failedPlugins, plan.skipped()));
        }

        return PluginResult.ofSuccess(isRestart ? "cascade-restart.success" : "cascade-reload.success",
                "plugin", targetPlugin.getName(),
                "count", String.valueOf(plan.reloadOrder().size()),
                "time", String.valueOf(System.currentTimeMillis() - startTime));
    }

    private CascadePlan planCascade(Plugin targetPlugin) {
        List<String> order = new ArrayList<>();
        for (String name : dependencyGraph.calculateCascadeOrder(targetPlugin.getName(), true)) {
            Plugin p = getPlugin(name);
            if (p == null || !isProtected(p)) {
                order.add(name);
            }
        }
        if (order.isEmpty()) {
            order.add(targetPlugin.getName());
        }
        Log.debug("lifecyclemanager.cascade-plan", "plugin", targetPlugin.getName(), "order", String.valueOf(order));

        Map<String, File> files = new HashMap<>();
        Map<String, Boolean> wasEnabled = new HashMap<>();
        List<String> reloadOrder = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String pluginName : order) {
            Plugin p = getPlugin(pluginName);
            if (p == null) continue;

            File jarFile = getPluginFile(p);
            if (jarFile == null || !jarFile.exists()) {
                skipped.add(pluginName);
                Log.warn("lifecyclemanager.cascade-no-jar", "plugin", pluginName);
                continue;
            }

            JarValidator.PreFlightReport report = JarValidator.validatePreFlight(jarFile, pluginName, true);
            if (!report.isValid()) {
                skipped.add(pluginName);
                Log.warn("lifecyclemanager.cascade-skipped", "plugin", pluginName, "error", String.valueOf(report.errorMessage()));
                continue;
            }

            files.put(pluginName, jarFile);
            wasEnabled.put(pluginName, p.isEnabled());
            reloadOrder.add(pluginName);
        }

        return new CascadePlan(reloadOrder, files, wasEnabled, skipped);
    }

    private UnloadPhase unloadAll(List<String> reloadOrder) {
        List<String> unloadOrder = new ArrayList<>(reloadOrder);
        Collections.reverse(unloadOrder);

        List<String> unloaded = new ArrayList<>();
        for (String pluginName : unloadOrder) {
            Plugin p = getPlugin(pluginName);
            if (p == null) continue;

            PluginResult result = bridge.unloadPlugin(p);
            if (!result.success()) {
                return new UnloadPhase(unloaded, pluginName, result.detail(message("actions.errors.details.unload-failed")));
            }
            invalidatePluginFile(p);
            unloaded.add(pluginName);
        }
        return new UnloadPhase(unloaded, null, null);
    }

    private String rollback(UnloadPhase unloaded, CascadePlan plan) {
        List<String> restoreOrder = new ArrayList<>(unloaded.unloaded());
        Collections.reverse(restoreOrder);

        List<String> rollbackFailed = new ArrayList<>();
        for (String pluginName : restoreOrder) {
            File jarFile = plan.files().get(pluginName);
            if (jarFile == null || !jarFile.isFile()) {
                rollbackFailed.add(pluginName);
                continue;
            }
            if (!bridge.loadPlugin(jarFile).success()) {
                rollbackFailed.add(pluginName);
                Log.warn("lifecyclemanager.cascade-rollback-failed", "plugin", pluginName);
            }
        }

        String note = rollbackFailed.isEmpty()
                ? message("actions.cascade-reload.details.rollback-done")
                : message("actions.cascade-reload.details.rollback-partial", "plugins", String.join(", ", rollbackFailed));
        return message("actions.cascade-reload.details.unload-failed",
                "plugin", unloaded.failedPlugin(), "error", unloaded.failedError(), "note", note);
    }

    private List<String> loadAll(CascadePlan plan) {
        List<String> failedPlugins = new ArrayList<>();
        Set<String> failedNames = new HashSet<>();
        Map<String, DependencyNode> graph = dependencyGraph.buildGraph(true);

        for (String pluginName : plan.reloadOrder()) {
            if (dependencyAlreadyFailed(graph, pluginName, failedNames)) {
                failedPlugins.add(message("actions.cascade-reload.details.dependency-failed", "plugin", pluginName));
                failedNames.add(pluginName.toLowerCase(Locale.ROOT));
                continue;
            }

            File jarFile = plan.files().get(pluginName);
            if (jarFile == null) continue;

            if (!bridge.loadPlugin(jarFile).success()) {
                failedPlugins.add(pluginName);
                failedNames.add(pluginName.toLowerCase(Locale.ROOT));
                continue;
            }

            Plugin reloaded = getPlugin(pluginName);
            if (reloaded != null && reloaded.isEnabled() && !plan.wasEnabled().getOrDefault(pluginName, true)) {
                bridge.disablePlugin(reloaded);
            }
        }
        return failedPlugins;
    }

    private boolean dependencyAlreadyFailed(Map<String, DependencyNode> graph, String pluginName, Set<String> failedNames) {
        DependencyNode node = graph.get(pluginName.toLowerCase(Locale.ROOT));
        if (node == null) {
            return false;
        }
        for (String dependency : node.getHardDependencies()) {
            if (failedNames.contains(dependency.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String describeProblems(List<String> failedPlugins, List<String> skipped) {
        StringBuilder problem = new StringBuilder();
        if (!failedPlugins.isEmpty()) {
            problem.append(message("actions.cascade-reload.details.load-failed", "plugins", String.join(", ", failedPlugins)));
        }
        if (!skipped.isEmpty()) {
            if (problem.length() > 0) problem.append("; ");
            problem.append(message("actions.cascade-reload.details.skipped", "plugins", String.join(", ", skipped)));
        }
        return problem.toString();
    }

    public @Nullable File findJarFile(@Nullable String query) {
        if (query == null || query.isBlank()) return null;

        File file = jarIndex.find(query);
        if (file == null) return null;

        if (!isValidPluginsPath(file)) {
            Log.warn("lifecyclemanager.file-outside-plugins-dir", "file", file.getAbsolutePath());
            return null;
        }
        return file.isFile() ? file : null;
    }

    private boolean isValidPluginsPath(@Nullable File file) {
        if (file == null || plugin == null || plugin.getDataFolder() == null) return false;
        try {
            File pluginsDir = plugin.getDataFolder().getParentFile();
            if (pluginsDir == null) return false;
            return file.getCanonicalFile().toPath().startsWith(pluginsDir.getCanonicalFile().toPath());
        } catch (IOException e) {
            Log.warn("lifecyclemanager.canonical-path-check-failed", e, "file", file.getName());
            return false;
        }
    }

    public boolean isProtected(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return true;
        if (isSelf(targetPlugin)) return true;
        return plugin != null && plugin.getConfigManager() != null && plugin.getConfigManager().isPluginIgnored(targetPlugin.getName());
    }

    public boolean isProtected(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return false;
        if (pluginName.equalsIgnoreCase("plugmanreloaded")) return true;
        if (plugin != null && pluginName.equalsIgnoreCase(plugin.getName())) return true;
        return plugin != null && plugin.getConfigManager() != null && plugin.getConfigManager().isPluginIgnored(pluginName);
    }

    public boolean isSelf(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return false;
        if (plugin != null && targetPlugin.equals(plugin)) return true;
        if (targetPlugin.getName().equalsIgnoreCase("plugmanreloaded")) return true;
        return plugin != null && targetPlugin.getName().equalsIgnoreCase(plugin.getName());
    }

    public FailureReason protectedReason(Plugin targetPlugin) {
        return isSelf(targetPlugin) ? FailureReason.SELF_PROTECTED : FailureReason.PLUGIN_IGNORED;
    }

    public FailureReason protectedReason(@Nullable String pluginName) {
        if (pluginName == null) return FailureReason.PLUGIN_IGNORED;
        boolean self = pluginName.equalsIgnoreCase("plugmanreloaded")
                || pluginName.equalsIgnoreCase(plugin.getName());
        return self ? FailureReason.SELF_PROTECTED : FailureReason.PLUGIN_IGNORED;
    }

    private String message(String key, String... placeholders) {
        return plugin.getConfigManager().text(key, placeholders);
    }

    public List<String> getLoadableNames() {
        return jarIndex.loadableNames();
    }

    public List<String> getLoadableNames(boolean useJar) {
        return jarIndex.loadableNames(useJar);
    }

    public List<String> getAllJarNames() {
        return jarIndex.allJarNames();
    }

    public @Nullable PluginInfo getPluginInfo(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return null;
        File file = getPluginFile(targetPlugin);
        boolean isPaper = bridge.isPaperPlugin(file);
        return PluginInfo.fromPlugin(targetPlugin, file, isPaper);
    }

    public BulkOperationResult bulkLoadJars(@Nullable List<PluginJarIndex.JarInfo> jars) {
        if (jars == null || jars.isEmpty()) {
            return BulkOperationResult.empty();
        }

        long start = System.currentTimeMillis();
        List<PluginJarIndex.JarInfo> sorted = dependencyGraph.sortUnloadedJarsTopologically(jars);
        List<String> successful = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, String> failureReasons = new LinkedHashMap<>();
        Set<String> failedNames = new HashSet<>();

        for (PluginJarIndex.JarInfo info : sorted) {
            String name = info.preferredName();
            PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(info.file());
            boolean depFailed = false;
            if (desc != null && desc.depend() != null) {
                for (String dep : desc.depend()) {
                    if (dep != null && failedNames.contains(dep.toLowerCase(Locale.ROOT))) {
                        depFailed = true;
                        break;
                    }
                }
            }

            if (depFailed) {
                failed.add(name);
                failureReasons.put(name, message("actions.errors.details.dependency-failed"));
                failedNames.add(name.toLowerCase(Locale.ROOT));
                continue;
            }

            PluginResult res = load(info.file());
            if (res.success()) {
                successful.add(name);
            } else {
                failed.add(name);
                String err = res.detail(message("actions.errors.details.load-failed"));
                failureReasons.put(name, err);
                failedNames.add(name.toLowerCase(Locale.ROOT));
            }
        }

        jarIndex.invalidate();
        brigadierManager.syncCommands();

        return new BulkOperationResult(jars.size(), successful.size(), successful, failed, failureReasons, System.currentTimeMillis() - start);
    }

    public BulkOperationResult bulkUnload(@Nullable List<Plugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return BulkOperationResult.empty();
        }

        List<Plugin> targets = plugins.stream().filter(p -> p != null && !isProtected(p)).toList();
        if (targets.isEmpty()) {
            return BulkOperationResult.empty();
        }

        long start = System.currentTimeMillis();
        List<Plugin> order = dependencyGraph.sortPluginsTopologically(targets);
        Collections.reverse(order);

        List<String> successful = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, String> failureReasons = new LinkedHashMap<>();

        for (Plugin p : order) {
            if (isProtected(p)) continue;
            PluginResult res = unload(p);
            if (res.success()) {
                successful.add(p.getName());
            } else {
                failed.add(p.getName());
                String err = res.detail(message("actions.errors.details.unload-failed"));
                failureReasons.put(p.getName(), err);
            }
        }

        jarIndex.invalidate();
        brigadierManager.syncCommands();

        return new BulkOperationResult(targets.size(), successful.size(), successful, failed, failureReasons, System.currentTimeMillis() - start);
    }

    public BulkOperationResult bulkEnable(@Nullable List<Plugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return BulkOperationResult.empty();
        }

        List<Plugin> targets = plugins.stream().filter(p -> p != null && !p.isEnabled()).toList();
        if (targets.isEmpty()) {
            return BulkOperationResult.empty();
        }

        long start = System.currentTimeMillis();
        List<Plugin> order = dependencyGraph.sortPluginsTopologically(targets);

        List<String> successful = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, String> failureReasons = new LinkedHashMap<>();

        for (Plugin p : order) {
            PluginResult res = enable(p);
            if (res.success()) {
                successful.add(p.getName());
            } else {
                failed.add(p.getName());
                String err = res.detail(message("actions.errors.details.enable-failed"));
                failureReasons.put(p.getName(), err);
            }
        }

        return new BulkOperationResult(targets.size(), successful.size(), successful, failed, failureReasons, System.currentTimeMillis() - start);
    }

    public BulkOperationResult bulkDisable(@Nullable List<Plugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return BulkOperationResult.empty();
        }

        List<Plugin> targets = plugins.stream().filter(p -> p != null && p.isEnabled() && !isProtected(p)).toList();
        if (targets.isEmpty()) {
            return BulkOperationResult.empty();
        }

        long start = System.currentTimeMillis();
        List<Plugin> order = dependencyGraph.sortPluginsTopologically(targets);
        Collections.reverse(order);

        List<String> successful = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, String> failureReasons = new LinkedHashMap<>();

        for (Plugin p : order) {
            if (isProtected(p)) continue;
            PluginResult res = disable(p);
            if (res.success()) {
                successful.add(p.getName());
            } else {
                failed.add(p.getName());
                String err = res.detail(message("actions.errors.details.disable-failed"));
                failureReasons.put(p.getName(), err);
            }
        }

        return new BulkOperationResult(targets.size(), successful.size(), successful, failed, failureReasons, System.currentTimeMillis() - start);
    }

    public BulkOperationResult bulkReload(List<Plugin> plugins) {
        return reloadAll(plugins);
    }

    public BulkOperationResult bulkRestart(List<Plugin> plugins) {
        return reloadAll(plugins);
    }

    private record BulkPlan(List<Plugin> valid, Map<String, File> files, Map<String, Boolean> wasEnabled) {}

    private BulkOperationResult reloadAll(@Nullable List<Plugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return BulkOperationResult.empty();
        }

        List<Plugin> targets = plugins.stream().filter(p -> p != null && !isProtected(p)).toList();
        if (targets.isEmpty()) {
            return BulkOperationResult.empty();
        }

        long start = System.currentTimeMillis();
        List<String> failed = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();

        BulkPlan plan = planBulk(targets, failed, reasons);
        if (plan.valid().isEmpty()) {
            return new BulkOperationResult(targets.size(), 0, Collections.emptyList(), failed, reasons,
                    System.currentTimeMillis() - start);
        }

        List<String> reloadOrder = dependencyGraph.sortPluginsTopologically(plan.valid())
                .stream().map(Plugin::getName).toList();
        List<String> unloaded = unloadForBulk(reloadOrder, failed, reasons);
        List<String> successful = loadForBulk(reloadOrder, unloaded, plan, failed, reasons);

        jarIndex.invalidate();
        brigadierManager.syncCommands();

        return new BulkOperationResult(targets.size(), successful.size(), successful, failed, reasons,
                System.currentTimeMillis() - start);
    }

    private BulkPlan planBulk(List<Plugin> targets, List<String> failed, Map<String, String> reasons) {
        Map<String, File> files = new HashMap<>();
        Map<String, Boolean> wasEnabled = new HashMap<>();
        List<Plugin> valid = new ArrayList<>();

        for (Plugin target : targets) {
            File jarFile = getPluginFile(target);
            if (jarFile == null || !jarFile.exists()) {
                failed.add(target.getName());
                reasons.put(target.getName(), message("actions.cascade-reload.details.no-jar-on-disk"));
                continue;
            }

            JarValidator.PreFlightReport report = JarValidator.validatePreFlight(jarFile, target.getName(), true);
            if (!report.isValid()) {
                failed.add(target.getName());
                reasons.put(target.getName(), report.errorMessage());
                continue;
            }

            files.put(target.getName(), jarFile);
            wasEnabled.put(target.getName(), target.isEnabled());
            valid.add(target);
        }
        return new BulkPlan(valid, files, wasEnabled);
    }

    private List<String> unloadForBulk(List<String> reloadOrder, List<String> failed, Map<String, String> reasons) {
        List<String> unloadOrder = new ArrayList<>(reloadOrder);
        Collections.reverse(unloadOrder);

        List<String> unloaded = new ArrayList<>();
        for (String pluginName : unloadOrder) {
            Plugin target = getPlugin(pluginName);
            if (target == null) continue;

            PluginResult result = bridge.unloadPlugin(target);
            if (result.success()) {
                unloaded.add(pluginName);
            } else {
                failed.add(pluginName);
                reasons.put(pluginName, result.detail(message("actions.errors.details.unload-failed")));
            }
        }
        return unloaded;
    }

    private List<String> loadForBulk(List<String> reloadOrder, List<String> unloaded, BulkPlan plan,
                                     List<String> failed, Map<String, String> reasons) {
        List<String> successful = new ArrayList<>();
        Set<String> failedNames = failed.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        Map<String, DependencyNode> graph = dependencyGraph.buildGraph(true);

        for (String pluginName : reloadOrder) {
            if (!unloaded.contains(pluginName)) {
                continue;
            }

            if (dependencyAlreadyFailed(graph, pluginName, failedNames)) {
                failed.add(pluginName);
                reasons.put(pluginName, message("actions.cascade-reload.details.dependency-failed",
                        "plugin", pluginName));
                failedNames.add(pluginName.toLowerCase(Locale.ROOT));
                continue;
            }

            File jarFile = plan.files().get(pluginName);
            if (jarFile == null) continue;

            PluginResult result = bridge.loadPlugin(jarFile);
            if (!result.success()) {
                failed.add(pluginName);
                reasons.put(pluginName, result.detail(message("actions.errors.details.load-failed")));
                failedNames.add(pluginName.toLowerCase(Locale.ROOT));
                continue;
            }

            successful.add(pluginName);
            Plugin reloaded = getPlugin(pluginName);
            if (reloaded != null && reloaded.isEnabled() && !plan.wasEnabled().getOrDefault(pluginName, true)) {
                bridge.disablePlugin(reloaded);
            }
        }
        return successful;
    }

}

