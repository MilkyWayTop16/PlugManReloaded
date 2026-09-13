package ru.milkyway.plugmanreloaded.update.install;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.DependencyNode;
import ru.milkyway.plugmanreloaded.managers.DependencyManager;
import ru.milkyway.plugmanreloaded.managers.UpdateDisplayManager;
import ru.milkyway.plugmanreloaded.update.UpdateModels.InstallResult;
import ru.milkyway.plugmanreloaded.update.UpdateModels.InstallStatus;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BatchUpdater {

    public interface BatchUpdateListener {
        void sendAction(@NotNull CommandSender sender, @NotNull String path);
        void sendAction(@NotNull CommandSender sender, @NotNull String path, @NotNull Map<String, String> placeholders);
        @NotNull String detailText(@Nullable String detail);
    }

    private final @NotNull PlugManReloaded plugin;
    private final @NotNull UpdateDisplayManager displayManager;

    public BatchUpdater(final @NotNull PlugManReloaded plugin, final @NotNull UpdateDisplayManager displayManager) {
        this.plugin = plugin;
        this.displayManager = displayManager;
    }

    public @NotNull List<UpdateCandidate> orderByDependencies(final @NotNull List<UpdateCandidate> installable) {
        final Map<String, UpdateCandidate> byName = new LinkedHashMap<>();
        for (final UpdateCandidate candidate : installable) {
            byName.put(candidate.identity().pluginName().toLowerCase(Locale.ROOT), candidate);
        }

        final List<UpdateCandidate> queue = new ArrayList<>();
        for (final String name : plugin.getPluginLifecycleManager().getDependencyManager()
                .sortNamesTopologically(byName.keySet())) {
            final UpdateCandidate candidate = byName.get(name.toLowerCase(Locale.ROOT));
            if (candidate != null && !queue.contains(candidate)) {
                queue.add(candidate);
            }
        }
        for (final UpdateCandidate candidate : installable) {
            if (!queue.contains(candidate)) {
                queue.add(candidate);
            }
        }
        return queue;
    }

    public void startBatchInstall(final @NotNull CommandSender sender,
                                  final @NotNull List<UpdateCandidate> withUpdates,
                                  final @NotNull BatchUpdateListener listener) {
        if (withUpdates.isEmpty()) {
            listener.sendAction(sender, "update.all-install-empty");
            return;
        }

        final List<UpdateCandidate> installable = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        for (final UpdateCandidate candidate : withUpdates) {
            if (candidate.installable() && !UpdateDisplayManager.isPaid(candidate)) {
                installable.add(candidate);
            } else {
                skipped.add(candidate.identity().pluginName());
            }
        }

        if (installable.isEmpty()) {
            if (skipped.isEmpty()) {
                listener.sendAction(sender, "update.all-install-empty");
            } else {
                listener.sendAction(sender, "update.all-install-skipped", Map.of(
                        "plugins", String.join(", ", skipped),
                        "count", String.valueOf(skipped.size())));
            }
            return;
        }

        final List<UpdateCandidate> queue = orderByDependencies(installable);
        listener.sendAction(sender, "update.all-install-start", Map.of(
                "count", String.valueOf(queue.size()),
                "total", String.valueOf(queue.size())));

        installSequentially(sender, queue, 0, System.currentTimeMillis(), 0, new ArrayList<>(), skipped, new ArrayList<>(), listener);
    }

    public void installSequentially(final @NotNull CommandSender sender,
                                    final @NotNull List<UpdateCandidate> queue,
                                    final int index,
                                    final long startTime,
                                    final int successCount,
                                    final @NotNull List<String> pendingRestartList,
                                    final @Nullable List<String> skippedList,
                                    final @NotNull List<UpdateCandidate> hotSwappedNow,
                                    final @NotNull BatchUpdateListener listener) {
        if (index >= queue.size()) {
            final long elapsed = System.currentTimeMillis() - startTime;
            final Map<String, String> summary = new HashMap<>();
            summary.put("count", String.valueOf(successCount));
            summary.put("total", String.valueOf(queue.size()));
            summary.put("time", String.valueOf(elapsed));
            if (successCount == 0 && !queue.isEmpty()) {
                listener.sendAction(sender, "update.all-install-failed", summary);
            } else if (successCount < queue.size()) {
                listener.sendAction(sender, "update.all-install-partial", summary);
            } else {
                listener.sendAction(sender, "update.all-install-success", summary);
            }

            if (skippedList != null && !skippedList.isEmpty()) {
                listener.sendAction(sender, "update.all-install-skipped", Map.of(
                        "count", String.valueOf(skippedList.size()),
                        "plugins", String.join(", ", skippedList)
                ));
            }

            reloadAffectedDependents(hotSwappedNow);
            return;
        }

        final UpdateCandidate candidate = queue.get(index);
        final Map<String, String> progressMap = displayManager.placeholders(candidate);
        progressMap.put("index", String.valueOf(index + 1));
        progressMap.put("total", String.valueOf(queue.size()));

        listener.sendAction(sender, "update.all-install-progress", progressMap);

        plugin.getUpdateService().install(candidate, false, result -> {
            int nextSuccess = successCount;
            if (result.outcome() == InstallStatus.INSTALLED || result.outcome() == InstallStatus.PENDING_RESTART) {
                nextSuccess++;
            }
            recordInstallOutcome(sender, queue, index, candidate, result, pendingRestartList, hotSwappedNow, listener);
            installSequentially(sender, queue, index + 1, startTime, nextSuccess, pendingRestartList, skippedList, hotSwappedNow, listener);
        });
    }

    public void recordInstallOutcome(final @NotNull CommandSender sender,
                                     final @NotNull List<UpdateCandidate> queue,
                                     final int index,
                                     final @NotNull UpdateCandidate candidate,
                                     final @NotNull InstallResult result,
                                     final @Nullable List<String> pendingRestartList,
                                     final @NotNull List<UpdateCandidate> hotSwappedNow,
                                     final @NotNull BatchUpdateListener listener) {
        final Map<String, String> itemMap = displayManager.placeholders(candidate);
        itemMap.put("index", String.valueOf(index + 1));
        itemMap.put("total", String.valueOf(queue.size()));
        itemMap.put("from", UpdateDisplayManager.cleanVersion(result.fromVersion()));
        itemMap.put("to", UpdateDisplayManager.cleanVersion(result.toVersion()));
        final String detail = result.detail() == null || result.detail().isBlank() ? "—" : listener.detailText(result.detail());
        itemMap.put("detail", detail);
        itemMap.put("error", detail);

        if (result.outcome() == InstallStatus.INSTALLED) {
            hotSwappedNow.add(candidate);
            listener.sendAction(sender, "update.all-install-item-success", itemMap);
        } else if (result.outcome() == InstallStatus.PENDING_RESTART) {
            if (pendingRestartList != null) {
                pendingRestartList.add(candidate.identity().pluginName());
            }
            listener.sendAction(sender, "update.all-install-item-pending", itemMap);
        } else {
            listener.sendAction(sender, "update.all-install-item-failed", itemMap);
        }
    }

    public void reloadAffectedDependents(final @Nullable List<UpdateCandidate> queue) {
        if (queue == null || queue.isEmpty()) return;

        final Set<String> queueNames = new HashSet<>();
        for (final UpdateCandidate c : queue) {
            if (c != null) {
                queueNames.add(c.identity().pluginName().toLowerCase(Locale.ROOT));
            }
        }

        final DependencyManager graphManager = plugin.getPluginLifecycleManager().getDependencyManager();
        final Map<String, DependencyNode> graph = graphManager.buildGraph(true);

        final Set<String> affected = new HashSet<>();
        for (final UpdateCandidate c : queue) {
            if (c == null) continue;
            final String name = c.identity().pluginName();
            final Set<String> deps = graphManager.getDependents(name, graph);
            for (final String dep : deps) {
                if (!queueNames.contains(dep.toLowerCase(Locale.ROOT))) {
                    final Plugin depPlugin = plugin.getPluginLifecycleManager().getPlugin(dep);
                    if (depPlugin != null && depPlugin.isEnabled() && !plugin.getPluginLifecycleManager().isProtected(depPlugin)) {
                        affected.add(dep);
                    }
                }
            }
        }

        if (affected.isEmpty()) {
            return;
        }

        final List<String> sorted = graphManager.sortNamesTopologically(affected);
        Log.info("updatecommand.reloading-dependents", "count", String.valueOf(sorted.size()), "plugins", String.join(", ", sorted));
        for (final String depName : sorted) {
            final Plugin p = plugin.getPluginLifecycleManager().getPlugin(depName);
            if (p != null && p.isEnabled() && !plugin.getPluginLifecycleManager().isProtected(p)) {
                plugin.getPluginLifecycleManager().reload(p);
            }
        }
    }
}