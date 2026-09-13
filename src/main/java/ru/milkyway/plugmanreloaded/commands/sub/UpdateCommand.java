package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.event.PluginUpdateFoundEvent;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.managers.DependencyManager;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;
import ru.milkyway.plugmanreloaded.managers.SafetyManager;
import ru.milkyway.plugmanreloaded.managers.UpdateDisplayManager;
import ru.milkyway.plugmanreloaded.managers.UpdateDisplayManager.UpdateTally;
import ru.milkyway.plugmanreloaded.update.UpdateModels.InstallResult;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.install.BatchUpdater;
import ru.milkyway.plugmanreloaded.update.install.BatchUpdater.BatchUpdateListener;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UpdateCommand extends AbstractSubCommand {

    private final UpdateDisplayManager displayManager;
    private final BatchUpdater batchUpdater;
    private final BatchUpdateListener batchListener;

    public UpdateCommand(final @Nullable PlugManReloaded plugin) {
        super(plugin);
        this.displayManager = new UpdateDisplayManager(plugin != null ? plugin.getConfigManager() : null);
        this.batchUpdater = new BatchUpdater(plugin, displayManager);
        this.batchListener = new BatchUpdateListener() {
            @Override
            public void sendAction(final @NotNull CommandSender sender, final @NotNull String path) {
                UpdateCommand.this.sendAction(sender, path);
            }

            @Override
            public void sendAction(final @NotNull CommandSender sender, final @NotNull String path, final @NotNull Map<String, String> placeholders) {
                UpdateCommand.this.sendAction(sender, path, placeholders);
            }

            @Override
            public @NotNull String detailText(final @Nullable String detail) {
                return UpdateCommand.this.detailText(detail);
            }
        };
    }

    @Override
    public String getName() {
        return "update";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.update";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(final @NotNull CommandContext ctx) {
        final CommandSender sender = ctx.sender();

        if (ctx.isCancel()) {
            return handleCancel(sender, ctx);
        }

        final List<String> args = ctx.positionalArgs();
        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("source")) {
            final String token = ctx.token() != null ? ctx.token() : (args.size() > 2 ? args.get(2) : "");
            return handleManualSource(sender, args.get(0), token);
        }

        if (ctx.target().equalsIgnoreCase("help")) {
            sendAction(sender, "help.update");
            return true;
        }

        final boolean refresh = ctx.hasFlag("r") || ctx.hasFlag("refresh");
        if (refresh && plugin != null) {
            plugin.getUpdateService().clearVersionsCache();
        }

        return !ctx.hasTarget() || ctx.isAll()
                ? checkEveryPlugin(sender, ctx, refresh)
                : checkOnePlugin(sender, ctx);
    }

    private boolean checkEveryPlugin(final @NotNull CommandSender sender, final @NotNull CommandContext ctx, final boolean refresh) {
        final String bulkPayload = plugin != null ? plugin.getConfirmationManager().peekPayload(sender, "update", "all") : null;
        boolean install = ctx.hasFlag("y") || ctx.hasFlag("yes") || ctx.hasFlag("f") || ctx.hasFlag("force");
        final String token = ctx.token();
        if (token != null) {
            if (!consumeBulkToken(sender, token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
            install = true;
        } else if (install && plugin != null) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "update", "all");
            plugin.getConfirmationManager().consumeIfPresent(sender, "update", "*");
        }

        final boolean prerelease = ctx.hasFlag("prerelease") || ctx.hasFlag("pre") || ctx.hasFlag("beta")
                || "prerelease".equalsIgnoreCase(bulkPayload)
                || (plugin != null && plugin.getConfigManager().isAllowPrerelease());
        final boolean isExplicitAll = ctx.isAll();
        if (!refresh && !ctx.hasFlag("prerelease") && !ctx.hasFlag("pre") && !ctx.hasFlag("beta") && plugin != null) {
            final List<UpdateCandidate> recent = plugin.getUpdateService().getRecentAllResults();
            if (recent != null && !recent.isEmpty()) {
                reportAll(sender, recent, install, isExplicitAll);
                return true;
            }
        }

        sendAction(sender, "update.check-start-all");
        final boolean doInstall = install;
        if (plugin != null) {
            plugin.getUpdateService().checkAll(prerelease, results -> reportAll(sender, results, doInstall, isExplicitAll));
        }
        return true;
    }

    private boolean consumeBulkToken(final @NotNull CommandSender sender, final @NotNull String token) {
        if (plugin == null) return false;
        final ConfirmationManager confirmations = plugin.getConfirmationManager();
        return confirmations.validateAndConsume(sender, "update", "all", token)
                || confirmations.validateAndConsume(sender, "update", "*", token);
    }

    private boolean checkOnePlugin(final @NotNull CommandSender sender, final @NotNull CommandContext ctx) {
        if (plugin == null) return true;
        final String targetName = ctx.target();
        final Plugin target = plugin.getPluginLifecycleManager().getPlugin(targetName);
        final File jarFile = target == null ? plugin.getPluginLifecycleManager().getJarIndex().find(targetName) : null;
        if (target == null && (jarFile == null || !jarFile.isFile())) {
            sendPluginNotFound(sender, targetName);
            return true;
        }

        final PluginJarIndex.JarDescriptor desc = target == null ? PluginJarIndex.readDescriptor(jarFile) : null;
        final String pluginName = target != null
                ? target.getName()
                : (desc != null && desc.declaredName() != null ? desc.declaredName() : targetName);

        final String sessionPayload = plugin.getConfirmationManager().peekPayload(sender, "update", pluginName);
        boolean install = ctx.hasFlag("y") || ctx.hasFlag("yes") || ctx.hasFlag("f") || ctx.hasFlag("force");
        final String token = ctx.token();
        if (token != null) {
            if (!plugin.getConfirmationManager().validateAndConsume(sender, "update", pluginName, token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
            install = true;
        } else if (install) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "update", pluginName);
        }

        final boolean doInstall = install;
        final boolean restartDependents = shouldRestartDependents(ctx, target);
        final boolean prerelease = ctx.hasFlag("prerelease") || ctx.hasFlag("pre") || ctx.hasFlag("beta")
                || "prerelease".equalsIgnoreCase(sessionPayload)
                || (plugin.getConfigManager().isAllowPrerelease());

        if (target != null) {
            if (!doInstall) {
                sendAction(sender, "update.check-start", Map.of("plugin", pluginName));
            }
            plugin.getUpdateService().checkOne(target, prerelease,
                    results -> reportSingle(sender, results, doInstall, restartDependents, pluginName));
            return true;
        }

        if (!doInstall) {
            final Map<String, String> placeholders = new HashMap<>();
            placeholders.put("plugin", pluginName);
            placeholders.put("file", jarFile.getName());
            placeholders.put("version", desc != null && desc.version() != null ? desc.version() : "1.0");
            sendAction(sender, "update.check-start", placeholders);
        }
        plugin.getUpdateService().checkOne(jarFile, prerelease,
                results -> reportSingle(sender, results, doInstall, restartDependents, pluginName));
        return true;
    }

    private boolean shouldRestartDependents(final @NotNull CommandContext ctx, final @Nullable Plugin target) {
        if (plugin == null || ctx.hasFlag("s") || ctx.hasFlag("single")) {
            return false;
        }
        if (ctx.hasFlag("c") || ctx.hasFlag("cascade") || plugin.getConfigManager().isCascadeReloadByDefault()) {
            return true;
        }
        return target != null && plugin.getPluginLifecycleManager().getSafetyManager()
                .assess(target).riskLevel() == SafetyManager.PluginRiskLevel.API_PROVIDER;
    }

    private boolean handleManualSource(final @NotNull CommandSender sender, final @Nullable String targetName, final @Nullable String token) {
        if (!sender.hasPermission("plugmanreloaded.update.source") && !sender.hasPermission("plugmanreloaded.admin")) {
            sendAction(sender, "errors.no-permission");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sendAction(sender, "update.manual-source.console-hint", Map.of("plugin", targetName != null ? targetName : ""));
            return true;
        }

        if (plugin == null) return true;

        if (token != null && !token.isEmpty()) {
            final boolean consumed = plugin.getConfirmationManager()
                    .validateAndConsume(sender, "update-source", targetName, token);
            if (!consumed) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
        }

        final Plugin target = plugin.getPluginLifecycleManager().getPlugin(targetName);
        final String mainClass = target != null ? target.getDescription().getMain() : "";
        plugin.getManualSources().start(player, targetName, mainClass);
        return true;
    }

    private boolean handleCancel(final @NotNull CommandSender sender, final @NotNull CommandContext ctx) {
        final String canceled = ctx.target();
        final String token = ctx.token();

        if (plugin != null && token != null && !token.isEmpty()) {
            boolean consumed = false;
            if (canceled != null && !canceled.isBlank()) {
                consumed = plugin.getConfirmationManager().validateAndConsume(sender, "update", canceled, token);
            }
            if (!consumed && (ctx.isAll() || canceled == null || canceled.isBlank() || canceled.equalsIgnoreCase("all") || canceled.equals("*"))) {
                consumed = plugin.getConfirmationManager().validateAndConsume(sender, "update", "all", token);
                if (!consumed) {
                    consumed = plugin.getConfirmationManager().validateAndConsume(sender, "update", "*", token);
                }
            }
            if (!consumed && canceled != null && !canceled.isBlank()) {
                consumed = plugin.getConfirmationManager().validateAndConsume(sender, "update-source", canceled, token);
            }
            if (!consumed) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
        }

        if (plugin != null && sender instanceof Player player && plugin.getManualSources().get(player) != null) {
            plugin.getManualSources().cancel(player);
            return true;
        }

        if (ctx.isAll() || (canceled != null && (canceled.equalsIgnoreCase("all") || canceled.equals("*") || canceled.isBlank()))) {
            sendAction(sender, "update.cancelled-all");
            return true;
        }

        sendAction(sender, "update.cancelled", Map.of("plugin", canceled != null ? canceled : ""));
        return true;
    }

    Set<String> dependentsOf(final @NotNull UpdateCandidate candidate) {
        if (plugin == null) return Set.of();
        final String pluginName = candidate.identity().pluginName();
        final Plugin target = plugin.getPluginLifecycleManager().getPlugin(pluginName);
        final Set<String> direct = target != null
                ? plugin.getPluginLifecycleManager().getSafetyManager().assess(target).dependents()
                : Set.of();
        return DependencyManager.resolveDependentsWithFallback(direct,
                plugin.getPluginLifecycleManager().getDependencyManager(), pluginName);
    }

    private void announceUpdateFound(final @NotNull UpdateCandidate candidate) {
        final Plugin matchedPlugin = Bukkit.getPluginManager().getPlugin(candidate.identity().pluginName());
        if (matchedPlugin == null) return;
        Bukkit.getPluginManager().callEvent(new PluginUpdateFoundEvent(matchedPlugin, candidate.toUpdateInfo()));
    }

    private void reportSingle(final @NotNull CommandSender sender,
                             final @NotNull List<UpdateCandidate> results,
                             final boolean install,
                             final boolean restartDependents,
                             final @NotNull String targetName) {
        if (results.isEmpty()) {
            sendAction(sender, "update.no-source", Map.of("plugin", targetName));
            return;
        }

        final UpdateCandidate candidate = results.get(0);
        if (candidate.status().hasNewerVersion()) {
            announceUpdateFound(candidate);
        }
        if (!install) {
            if (candidate.installable()) {
                offerInstall(sender, candidate);
            } else if (UpdateDisplayManager.isPaid(candidate)) {
                sendAction(sender, "update.paid", displayManager.placeholders(candidate));
            } else if (candidate.status() == UpdateStatus.NO_SOURCE) {
                if (plugin != null && sender instanceof Player && plugin.getConfigManager().isManualSourceEnabled()
                        && (sender.hasPermission("plugmanreloaded.update.source") || sender.hasPermission("plugmanreloaded.admin"))) {
                    final String token = plugin.getConfirmationManager()
                            .createSession(sender, "update-source", candidate.identity().pluginName());
                    final Map<String, String> map = displayManager.placeholders(candidate);
                    map.put("token", token);
                    map.put("cmd-type", "update");
                    sendAction(sender, "update.manual-source.ask", map);
                } else {
                    sendAction(sender, candidate.status().actionKey(), displayManager.placeholders(candidate));
                }
            } else if (blockedByGithubLimit(candidate)) {
                sendAction(sender, "update.github-rate-limited", displayManager.placeholders(candidate));
            } else {
                sendAction(sender, candidate.status().actionKey(), displayManager.placeholders(candidate));
            }
            return;
        }

        startInstall(sender, candidate, restartDependents);
    }

    private void offerInstall(final @NotNull CommandSender sender, final @NotNull UpdateCandidate candidate) {
        if (plugin == null) return;
        final boolean isPrerelease = candidate.status() == UpdateStatus.PRERELEASE_ONLY
                || (candidate.version() != null && candidate.version().channel() != null && candidate.version().channel().isPrerelease());
        final String token = plugin.getConfirmationManager()
                .createSession(sender, "update", candidate.identity().pluginName(), isPrerelease ? "prerelease" : null);

        final Map<String, String> map = displayManager.placeholders(candidate);
        map.put("token", token);
        map.put("cmd-type", "update");
        map.put("is-prerelease", String.valueOf(isPrerelease));

        final Set<String> dependents = dependentsOf(candidate);
        final String noneDependents = plugin.getConfigManager().text("actions.update.no-dependents");
        map.put("dependents", dependents.isEmpty() ? noneDependents : String.join(", ", dependents));
        map.put("dependents-count", String.valueOf(dependents.size()));

        final String key = switch (candidate.status()) {
            case PRERELEASE_ONLY -> "update.confirm-prerelease";
            case COMPAT_UNKNOWN -> "update.confirm-compat-unknown";
            case AMBIGUOUS_MATCH -> "update.confirm-ambiguous";
            default -> !dependents.isEmpty() ? "update.confirm-dependents" : "update.confirm";
        };
        sendAction(sender, key, map);
    }

    private boolean blockedByGithubLimit(final @NotNull UpdateCandidate candidate) {
        return plugin != null
                && candidate.status() == UpdateStatus.FOUND_NOT_DOWNLOADABLE
                && candidate.version() != null
                && "github".equals(candidate.version().sourceId())
                && plugin.getUpdateService().isGithubRateLimited();
    }

    private void startInstall(final @NotNull CommandSender sender, final @NotNull UpdateCandidate candidate, final boolean restartDependents) {
        if (!candidate.installable()) {
            if (UpdateDisplayManager.isPaid(candidate)) {
                sendAction(sender, "update.paid", displayManager.placeholders(candidate));
                return;
            }
            if (blockedByGithubLimit(candidate)) {
                sendAction(sender, "update.github-rate-limited", displayManager.placeholders(candidate));
                return;
            }
            sendAction(sender, candidate.status().actionKey(), displayManager.placeholders(candidate));
            return;
        }

        sendAction(sender, "update.install-start", displayManager.placeholders(candidate));
        if (plugin != null) {
            plugin.getUpdateService().install(candidate, restartDependents, result -> reportInstall(sender, candidate, result));
        }
    }

    private void reportInstall(final @NotNull CommandSender sender, final @NotNull UpdateCandidate candidate, final @NotNull InstallResult result) {
        final Map<String, String> map = displayManager.placeholders(candidate);
        final String detail = result.detail() == null || result.detail().isBlank() ? "—" : detailText(result.detail());
        map.put("detail", detail);
        map.put("error", detail);
        map.put("dependencies", detail);
        map.put("from", UpdateDisplayManager.cleanVersion(result.fromVersion()));
        map.put("to", UpdateDisplayManager.cleanVersion(result.toVersion()));
        sendAction(sender, result.outcome().actionKey(), map);

        if (result.dependencyWarnings() != null && !result.dependencyWarnings().isEmpty()) {
            for (String depWarn : result.dependencyWarnings()) {
                String[] parts = depWarn.split(":", 2);
                String depName = parts[0];
                String depVer = parts.length > 1 ? parts[1] : "";
                Map<String, String> depMap = new HashMap<>(map);
                depMap.put("dependency", depName);
                depMap.put("dep_version", depVer);
                sendAction(sender, "update.dependency-notice", depMap);
            }
        }
    }

    private void reportAll(final @NotNull CommandSender sender,
                           final @NotNull List<UpdateCandidate> results,
                           final boolean install,
                           final boolean isExplicitAll) {
        if (results.isEmpty()) {
            sendAction(sender, "update.summary-empty");
            return;
        }

        final UpdateTally tally = displayManager.tally(results, this::announceUpdateFound);
        final Map<String, String> summary = displayManager.summarize(results.size(), tally);
        final String rendered = displayManager.renderSummaryList(tally.withUpdates());
        summary.put("updates", rendered);
        summary.put("plugins", rendered);

        if (install) {
            installAll(sender, tally.withUpdates());
        } else {
            showSummary(sender, summary, tally.withUpdates(), isExplicitAll);
        }
    }

    private void showSummary(final @NotNull CommandSender sender,
                            final @NotNull Map<String, String> summary,
                            final @NotNull List<UpdateCandidate> withUpdates,
                            final boolean isExplicitAll) {
        if (withUpdates.isEmpty()) {
            final FileConfiguration cfg = config();
            final String emptyMessage = cfg != null ? cfg.getString("actions.update.none-available", "") : "";
            summary.put("updates", emptyMessage);
            summary.put("plugins", emptyMessage);
            if (cfg != null && cfg.contains("actions.update.summary-up-to-date")) {
                sendAction(sender, "update.summary-up-to-date", summary);
                return;
            }
        } else if (isExplicitAll && plugin != null) {
            final boolean hasPrerelease = withUpdates.stream()
                    .anyMatch(c -> c.version() != null && c.version().channel() != null && c.version().channel().isPrerelease());
            summary.put("token", plugin.getConfirmationManager().createSession(sender, "update", "all", hasPrerelease ? "prerelease" : null));
            summary.put("is-prerelease", String.valueOf(hasPrerelease));
            sendAction(sender, "update.confirm-all", summary);
            return;
        }
        sendAction(sender, "update.summary", summary);
    }

    private void installAll(final @NotNull CommandSender sender, final @NotNull List<UpdateCandidate> withUpdates) {
        batchUpdater.startBatchInstall(sender, withUpdates, batchListener);
    }

    void recordInstallOutcome(final @NotNull CommandSender sender,
                              final @NotNull List<UpdateCandidate> queue,
                              final int index,
                              final @NotNull UpdateCandidate candidate,
                              final @NotNull InstallResult result,
                              final @Nullable List<String> pendingRestartList,
                              final @NotNull List<UpdateCandidate> hotSwappedNow) {
        batchUpdater.recordInstallOutcome(sender, queue, index, candidate, result, pendingRestartList, hotSwappedNow, batchListener);
    }

    private @Nullable FileConfiguration config() {
        return plugin != null ? plugin.getConfigManager().getMessagesConfig() : null;
    }

    String cleanVersion(final @Nullable String value) {
        return UpdateDisplayManager.cleanVersion(value);
    }

    String formatChannel(final @Nullable String channelKey) {
        return displayManager.formatChannel(channelKey);
    }

    String formatSource(final @Nullable String sourceId) {
        return displayManager.formatSource(sourceId);
    }

    Map<String, String> placeholders(final @NotNull UpdateCandidate candidate) {
        return displayManager.placeholders(candidate);
    }

    @Override
    public List<String> tabCandidates(int argLength, String previousToken, Set<String> usedTokens, CommandSender sender) {
        if (argLength == 2) {
            List<String> list = withAllFlag(usedTokens, suggestFlags(usedTokens));
            list.addAll(allDeletablePlugins());
            return list;
        }
        List<String> list = new ArrayList<>(suggestFlags(usedTokens));
        boolean maySetSource = sender == null
                || sender.hasPermission("plugmanreloaded.update.source")
                || sender.hasPermission("plugmanreloaded.admin");
        if (argLength == 3 && maySetSource) {
            list.add("source");
        }
        return list;
    }
}
