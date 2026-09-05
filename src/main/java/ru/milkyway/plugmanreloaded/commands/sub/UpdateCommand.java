package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.DependencyNode;
import ru.milkyway.plugmanreloaded.api.UpdateInfo;
import ru.milkyway.plugmanreloaded.api.event.PluginUpdateFoundEvent;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.DependencyGraph;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.managers.UnloadSafetyChecker;
import ru.milkyway.plugmanreloaded.update.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.install.InstallStatus;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import ru.milkyway.plugmanreloaded.update.install.InstallResult;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class UpdateCommand extends AbstractSubCommand {

    public UpdateCommand(PlugManReloaded plugin) {
        super(plugin);
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
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.isCancel()) {
            return handleCancel(sender, ctx);
        }

        List<String> args = ctx.positionalArgs();
        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("source")) {
            String token = ctx.token() != null ? ctx.token() : (args.size() > 2 ? args.get(2) : "");
            return handleManualSource(sender, args.get(0), token);
        }

        if (ctx.target().equalsIgnoreCase("help")) {
            sendAction(sender, "help.update");
            return true;
        }

        boolean refresh = ctx.hasFlag("r") || ctx.hasFlag("refresh");
        if (refresh) {
            plugin.getUpdateService().clearVersionsCache();
        }

        return !ctx.hasTarget() || ctx.isAll()
                ? checkEveryPlugin(sender, ctx, refresh)
                : checkOnePlugin(sender, ctx);
    }

    private boolean checkEveryPlugin(CommandSender sender, CommandContext ctx, boolean refresh) {
        boolean install = ctx.hasFlag("y") || ctx.hasFlag("yes") || ctx.hasFlag("f") || ctx.hasFlag("force");
        String token = ctx.token();
        if (token != null) {
            if (!consumeBulkToken(sender, token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
            install = true;
        } else if (install) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "update", "all");
            plugin.getConfirmationManager().consumeIfPresent(sender, "update", "*");
        }

        boolean isExplicitAll = ctx.isAll();
        if (!refresh) {
            List<UpdateCandidate> recent = plugin.getUpdateService().getRecentAllResults();
            if (recent != null && !recent.isEmpty()) {
                reportAll(sender, recent, install, isExplicitAll);
                return true;
            }
        }

        sendAction(sender, "update.check-start-all");
        boolean doInstall = install;
        plugin.getUpdateService().checkAll(results -> reportAll(sender, results, doInstall, isExplicitAll));
        return true;
    }

    private boolean consumeBulkToken(CommandSender sender, String token) {
        ConfirmationManager confirmations = plugin.getConfirmationManager();
        return confirmations.validateAndConsume(sender, "update", "all", token)
                || confirmations.validateAndConsume(sender, "update", "*", token);
    }

    private boolean checkOnePlugin(CommandSender sender, CommandContext ctx) {
        String targetName = ctx.target();
        Plugin target = plugin.getPluginLifecycleManager().getPlugin(targetName);
        File jarFile = target == null ? plugin.getPluginLifecycleManager().getJarIndex().find(targetName) : null;
        if (target == null && (jarFile == null || !jarFile.isFile())) {
            sendAction(sender, "errors.plugin-not-found", Map.of("plugin", targetName));
            return true;
        }

        PluginJarIndex.JarDescriptor desc = target == null ? PluginJarIndex.readDescriptor(jarFile) : null;
        String pluginName = target != null
                ? target.getName()
                : (desc != null && desc.declaredName() != null ? desc.declaredName() : targetName);

        boolean install = ctx.hasFlag("y") || ctx.hasFlag("yes") || ctx.hasFlag("f") || ctx.hasFlag("force");
        String token = ctx.token();
        if (token != null) {
            if (!plugin.getConfirmationManager().validateAndConsume(sender, "update", pluginName, token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
            install = true;
        } else if (install) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "update", pluginName);
        }

        boolean doInstall = install;
        boolean restartDependents = shouldRestartDependents(ctx, target);

        if (target != null) {
            if (!doInstall) {
                sendAction(sender, "update.check-start", Map.of("plugin", pluginName));
            }
            plugin.getUpdateService().checkOne(target,
                    results -> reportSingle(sender, results, doInstall, restartDependents, pluginName));
            return true;
        }

        if (!doInstall) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("plugin", pluginName);
            placeholders.put("file", jarFile.getName());
            placeholders.put("version", desc != null && desc.version() != null ? desc.version() : "1.0");
            sendAction(sender, "update.check-start", placeholders);
        }
        plugin.getUpdateService().checkOne(jarFile,
                results -> reportSingle(sender, results, doInstall, restartDependents, pluginName));
        return true;
    }

    private boolean shouldRestartDependents(CommandContext ctx, @Nullable Plugin target) {
        if (ctx.hasFlag("s") || ctx.hasFlag("single")) {
            return false;
        }
        if (ctx.hasFlag("c") || ctx.hasFlag("cascade") || plugin.getConfigManager().isCascadeReloadByDefault()) {
            return true;
        }
        return target != null && plugin.getPluginLifecycleManager().getSafetyAdvisor()
                .assess(target).riskLevel() == UnloadSafetyChecker.PluginRiskLevel.API_PROVIDER;
    }

    private boolean handleManualSource(CommandSender sender, String targetName, String token) {
        if (!sender.hasPermission("plugmanreloaded.update.source") && !sender.hasPermission("plugmanreloaded.admin")) {
            sendAction(sender, "errors.no-permission");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sendAction(sender, "update.manual-source.console-hint", Map.of("plugin", targetName != null ? targetName : ""));
            return true;
        }

        if (token != null && !token.isEmpty()) {
            boolean consumed = plugin.getConfirmationManager()
                    .validateAndConsume(sender, "update-source", targetName, token);
            if (!consumed) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
        }

        Plugin target = plugin.getPluginLifecycleManager().getPlugin(targetName);
        String mainClass = target != null ? target.getDescription().getMain() : "";
        plugin.getManualSources().start(player, targetName, mainClass);
        return true;
    }

    private boolean handleCancel(CommandSender sender, CommandContext ctx) {
        String canceled = ctx.target();
        String token = ctx.token();

        if (token != null && !token.isEmpty()) {
            boolean consumed = false;
            if (canceled != null && !canceled.isBlank()) {
                consumed = plugin.getConfirmationManager()
                        .validateAndConsume(sender, "update", canceled, token);
            }
            if (!consumed && (ctx.isAll() || canceled == null || canceled.isBlank() || canceled.equalsIgnoreCase("all") || canceled.equals("*"))) {
                consumed = plugin.getConfirmationManager()
                        .validateAndConsume(sender, "update", "all", token);
                if (!consumed) {
                    consumed = plugin.getConfirmationManager()
                            .validateAndConsume(sender, "update", "*", token);
                }
            }
            if (!consumed && canceled != null && !canceled.isBlank()) {
                consumed = plugin.getConfirmationManager()
                        .validateAndConsume(sender, "update-source", canceled, token);
            }
            if (!consumed) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
        }

        if (sender instanceof Player player && plugin.getManualSources().get(player) != null) {
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

    private Set<String> dependentsOf(UpdateCandidate candidate) {
        String pluginName = candidate.identity().pluginName();
        Plugin target = plugin.getPluginLifecycleManager().getPlugin(pluginName);
        Set<String> direct = target != null
                ? plugin.getPluginLifecycleManager().getSafetyAdvisor().assess(target).dependents()
                : Set.of();
        return DependencyGraph.resolveDependentsWithFallback(direct,
                plugin.getPluginLifecycleManager().getDependencyGraph(), pluginName);
    }

    private void announceUpdateFound(UpdateCandidate candidate) {
        Plugin matchedPlugin = Bukkit.getPluginManager().getPlugin(candidate.identity().pluginName());
        if (matchedPlugin == null) return;
        Bukkit.getPluginManager().callEvent(new PluginUpdateFoundEvent(
                matchedPlugin, UpdateInfo.from(candidate)));
    }

    private void reportSingle(CommandSender sender, List<UpdateCandidate> results, boolean install, boolean restartDependents, String targetName) {
        if (results.isEmpty()) {
            sendAction(sender, "update.no-source", Map.of("plugin", targetName));
            return;
        }

        UpdateCandidate candidate = results.get(0);
        if (candidate.status().hasNewerVersion()) {
            announceUpdateFound(candidate);
        }
        if (!install) {
            if (candidate.installable()) {
                offerInstall(sender, candidate);
            } else if (isPaid(candidate)) {
                sendAction(sender, "update.paid", placeholders(candidate));
            } else if (candidate.status() == UpdateStatus.NO_SOURCE) {
                if (sender instanceof Player && plugin.getConfigManager().isManualSourceEnabled()
                        && (sender.hasPermission("plugmanreloaded.update.source") || sender.hasPermission("plugmanreloaded.admin"))) {
                    String token = plugin.getConfirmationManager()
                            .createSession(sender, "update-source", candidate.identity().pluginName());
                    Map<String, String> map = placeholders(candidate);
                    map.put("token", token);
                    map.put("cmd-type", "update");
                    sendAction(sender, "update.manual-source.ask", map);
                } else {
                    sendAction(sender, candidate.status().actionKey(), placeholders(candidate));
                }
            } else if (blockedByGithubLimit(candidate)) {
                sendAction(sender, "update.github-rate-limited", placeholders(candidate));
            } else {
                sendAction(sender, candidate.status().actionKey(), placeholders(candidate));
            }
            return;
        }

        startInstall(sender, candidate, restartDependents);
    }

    private boolean isPaid(@Nullable UpdateCandidate candidate) {
        if (candidate == null) return false;
        if (candidate.identity().isPremium()) {
            return true;
        }
        return candidate.version() != null && (UpdateSource.isPaidSource(candidate.version().sourceId()) || !candidate.version().downloadable());
    }

    private void offerInstall(CommandSender sender, UpdateCandidate candidate) {
        String token = plugin.getConfirmationManager()
                .createSession(sender, "update", candidate.identity().pluginName());

        Map<String, String> map = placeholders(candidate);
        map.put("token", token);
        map.put("cmd-type", "update");

        Set<String> dependents = dependentsOf(candidate);
        String noneDependents = plugin.getConfigManager().text("actions.update.no-dependents");
        map.put("dependents", dependents.isEmpty() ? noneDependents : String.join(", ", dependents));
        map.put("dependents-count", String.valueOf(dependents.size()));

        String key;
        if (candidate.status() == UpdateStatus.PRERELEASE_ONLY) {
            key = "update.confirm-prerelease";
        } else if (candidate.status() == UpdateStatus.COMPAT_UNKNOWN) {
            key = "update.confirm-compat-unknown";
        } else if (candidate.status() == UpdateStatus.AMBIGUOUS_MATCH) {
            key = "update.confirm-ambiguous";
        } else if (!dependents.isEmpty()) {
            key = "update.confirm-dependents";
        } else {
            key = "update.confirm";
        }
        sendAction(sender, key, map);
    }

    private boolean blockedByGithubLimit(UpdateCandidate candidate) {
        return candidate.status() == UpdateStatus.FOUND_NOT_DOWNLOADABLE
                && candidate.version() != null
                && "github".equals(candidate.version().sourceId())
                && plugin.getUpdateService().isGithubRateLimited();
    }

    private void startInstall(CommandSender sender, UpdateCandidate candidate, boolean restartDependents) {
        if (!candidate.installable()) {
            if (isPaid(candidate)) {
                sendAction(sender, "update.paid", placeholders(candidate));
                return;
            }
            if (blockedByGithubLimit(candidate)) {
                sendAction(sender, "update.github-rate-limited", placeholders(candidate));
                return;
            }
            sendAction(sender, candidate.status().actionKey(), placeholders(candidate));
            return;
        }

        sendAction(sender, "update.install-start", placeholders(candidate));
        plugin.getUpdateService().install(candidate, restartDependents, result -> reportInstall(sender, candidate, result));
    }

    private void reportInstall(CommandSender sender, UpdateCandidate candidate, InstallResult result) {
        Map<String, String> map = placeholders(candidate);
        String detail = result.detail() == null || result.detail().isBlank() ? "—" : detailText(result.detail());
        map.put("detail", detail);
        map.put("error", detail);
        map.put("dependencies", detail);
        map.put("from", cleanVersion(result.fromVersion()));
        map.put("to", cleanVersion(result.toVersion()));
        sendAction(sender, result.outcome().actionKey(), map);
    }

    private record SummaryEntry(String text, String hover, String command) {

        private static final String DEFAULT_TEXT =
                "&#FFFF00◆ &f{plugin} &7{current} &f→ &#00FF5A{latest} &7({source}, {channel})";
        private static final String DEFAULT_HOVER =
                "\n &#00FF5A▶ &fUpdate for &#00FF5A«{plugin}» \n\n &#FFFF00◆ &fCurrent version: &7v{current} \n"
                + " &#00FF5A◆ &fNew version: &#00FF5Av{latest} \n &#FFFF00◆ &fSource: &#FFFF00{source} &7({channel}) \n\n"
                + " &#00FF5A▶ &fClick to &#00FF5Adownload and install &fthe update \n";
        private static final String DEFAULT_COMMAND = "/plm update {plugin}";
    }

    private record UpdateTally(List<UpdateCandidate> withUpdates, int upToDate, int noSource, int problems) {}

    private void reportAll(CommandSender sender, List<UpdateCandidate> results, boolean install, boolean isExplicitAll) {
        if (results.isEmpty()) {
            sendAction(sender, "update.summary-empty");
            return;
        }

        UpdateTally tally = tally(results);
        Map<String, String> summary = summarize(results.size(), tally);
        String rendered = renderSummaryList(tally.withUpdates());
        summary.put("updates", rendered);
        summary.put("plugins", rendered);

        if (install) {
            installAll(sender, tally.withUpdates());
        } else {
            showSummary(sender, summary, tally.withUpdates(), isExplicitAll);
        }
    }

    private UpdateTally tally(List<UpdateCandidate> results) {
        List<UpdateCandidate> withUpdates = new ArrayList<>();
        int upToDate = 0;
        int noSource = 0;
        int problems = 0;

        for (UpdateCandidate candidate : results) {
            if (candidate.status().hasNewerVersion()) {
                withUpdates.add(candidate);
                announceUpdateFound(candidate);
            } else if (candidate.status() == UpdateStatus.UP_TO_DATE || candidate.status() == UpdateStatus.PENDING_RESTART) {
                upToDate++;
            } else if (candidate.status() == UpdateStatus.NO_SOURCE) {
                noSource++;
            } else {
                problems++;
            }
        }
        return new UpdateTally(withUpdates, upToDate, noSource, problems);
    }

    private Map<String, String> summarize(int total, UpdateTally tally) {
        Map<String, String> summary = new HashMap<>();
        summary.put("total", String.valueOf(total));
        summary.put("available", String.valueOf(tally.withUpdates().size()));
        summary.put("count", String.valueOf(tally.withUpdates().size()));
        summary.put("up-to-date", String.valueOf(tally.upToDate()));
        summary.put("no-source", String.valueOf(tally.noSource()));
        summary.put("problems", String.valueOf(tally.problems()));
        summary.put("cmd-type", "update");
        summary.put("plugin", "all");
        return summary;
    }

    private SummaryEntry readSummaryEntry() {
        FileConfiguration config = config();
        String path = "actions.update.summary-entry";

        if (config.isConfigurationSection(path)) {
            String text = config.getString(path + ".text", SummaryEntry.DEFAULT_TEXT);
            String hover = SummaryEntry.DEFAULT_HOVER;
            if (config.isList(path + ".hover")) {
                hover = String.join("<newline>", config.getStringList(path + ".hover"));
            } else if (config.isString(path + ".hover")) {
                hover = config.getString(path + ".hover", hover);
            }
            return new SummaryEntry(text, hover, config.getString(path + ".command", SummaryEntry.DEFAULT_COMMAND));
        }

        String custom = null;
        if (config.isString(path)) {
            custom = config.getString(path);
        } else if (config.isList(path)) {
            List<String> list = config.getStringList(path);
            custom = list.isEmpty() ? null : list.get(0);
        }
        if (custom == null || custom.isBlank()) {
            return new SummaryEntry(SummaryEntry.DEFAULT_TEXT, SummaryEntry.DEFAULT_HOVER, SummaryEntry.DEFAULT_COMMAND);
        }
        String text = custom.startsWith("[message]") ? custom.substring(9).trim() : custom.trim();
        return new SummaryEntry(text, SummaryEntry.DEFAULT_HOVER, SummaryEntry.DEFAULT_COMMAND);
    }

    private String renderSummaryList(List<UpdateCandidate> withUpdates) {
        SummaryEntry template = readSummaryEntry();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < withUpdates.size(); i++) {
            if (i > 0) builder.append("\n");
            builder.append(renderSummaryLine(withUpdates.get(i), template));
        }
        return builder.toString();
    }

    private String renderSummaryLine(UpdateCandidate candidate, SummaryEntry template) {
        Map<String, String> values = placeholders(candidate);
        String text = applyPlaceholders(template.text(), values);
        String hover = applyPlaceholders(template.hover(), values);
        String command = applyPlaceholders(template.command(), values);

        if (isPaid(candidate)) {
            command = "";
            hover = "";
        }

        String miniHover = hover.isBlank() ? "" : HexColors.toMiniMessage(hover);
        if (!command.isBlank() && !miniHover.isBlank()) {
            return "<click:run_command:\"" + command.replace("\"", "\\\"") + "\"><hover:show_text:\""
                    + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
        }
        if (!command.isBlank()) {
            return "<click:run_command:\"" + command.replace("\"", "\\\"") + "\">" + text + "</click>";
        }
        if (!miniHover.isBlank()) {
            return "<hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover>";
        }
        return text;
    }

    private static String applyPlaceholders(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{" + entry.getKey() + "}", value)
                    .replace("%" + entry.getKey() + "%", value);
        }
        return result;
    }

    private void showSummary(CommandSender sender, Map<String, String> summary,
                             List<UpdateCandidate> withUpdates, boolean isExplicitAll) {
        if (withUpdates.isEmpty()) {
            String emptyMessage = config().getString("actions.update.none-available", "");
            summary.put("updates", emptyMessage);
            summary.put("plugins", emptyMessage);
            if (config().contains("actions.update.summary-up-to-date")) {
                sendAction(sender, "update.summary-up-to-date", summary);
                return;
            }
        } else if (isExplicitAll) {
            summary.put("token", plugin.getConfirmationManager().createSession(sender, "update", "all"));
            sendAction(sender, "update.confirm-all", summary);
            return;
        }
        sendAction(sender, "update.summary", summary);
    }

    private void installAll(CommandSender sender, List<UpdateCandidate> withUpdates) {
        if (withUpdates.isEmpty()) {
            sendAction(sender, "update.all-install-empty");
            return;
        }

        List<UpdateCandidate> installable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (UpdateCandidate candidate : withUpdates) {
            if (candidate.installable() && !isPaid(candidate)) {
                installable.add(candidate);
            } else {
                skipped.add(candidate.identity().pluginName());
            }
        }

        if (installable.isEmpty()) {
            if (skipped.isEmpty()) {
                sendAction(sender, "update.all-install-empty");
            } else {
                sendAction(sender, "update.all-install-skipped", Map.of(
                        "plugins", String.join(", ", skipped),
                        "count", String.valueOf(skipped.size())));
            }
            return;
        }

        List<UpdateCandidate> queue = orderByDependencies(installable);
        sendAction(sender, "update.all-install-start", Map.of(
                "count", String.valueOf(queue.size()),
                "total", String.valueOf(queue.size())));

        installSequentially(sender, queue, 0, System.currentTimeMillis(), 0, new ArrayList<>(), skipped, new ArrayList<>());
    }

    private List<UpdateCandidate> orderByDependencies(List<UpdateCandidate> installable) {
        Map<String, UpdateCandidate> byName = new LinkedHashMap<>();
        for (UpdateCandidate candidate : installable) {
            byName.put(candidate.identity().pluginName().toLowerCase(Locale.ROOT), candidate);
        }

        List<UpdateCandidate> queue = new ArrayList<>();
        for (String name : plugin.getPluginLifecycleManager().getDependencyGraph()
                .sortNamesTopologically(byName.keySet())) {
            UpdateCandidate candidate = byName.get(name.toLowerCase(Locale.ROOT));
            if (candidate != null && !queue.contains(candidate)) {
                queue.add(candidate);
            }
        }
        for (UpdateCandidate candidate : installable) {
            if (!queue.contains(candidate)) {
                queue.add(candidate);
            }
        }
        return queue;
    }

    private void installSequentially(CommandSender sender, List<UpdateCandidate> queue, int index,
                                     long startTime, int successCount, List<String> pendingRestartList,
                                     List<String> skippedList, List<UpdateCandidate> hotSwappedNow) {
        if (index >= queue.size()) {
            long elapsed = System.currentTimeMillis() - startTime;
            Map<String, String> summary = new HashMap<>();
            summary.put("count", String.valueOf(successCount));
            summary.put("total", String.valueOf(queue.size()));
            summary.put("time", String.valueOf(elapsed));
            if (successCount == 0 && !queue.isEmpty()) {
                sendAction(sender, "update.all-install-failed", summary);
            } else if (successCount < queue.size()) {
                sendAction(sender, "update.all-install-partial", summary);
            } else {
                sendAction(sender, "update.all-install-success", summary);
            }

            if (skippedList != null && !skippedList.isEmpty()) {
                sendAction(sender, "update.all-install-skipped", Map.of(
                        "count", String.valueOf(skippedList.size()),
                        "plugins", String.join(", ", skippedList)
                ));
            }

            reloadAffectedDependents(hotSwappedNow);
            return;
        }

        UpdateCandidate candidate = queue.get(index);
        Map<String, String> progressMap = placeholders(candidate);
        progressMap.put("index", String.valueOf(index + 1));
        progressMap.put("total", String.valueOf(queue.size()));

        sendAction(sender, "update.all-install-progress", progressMap);

        plugin.getUpdateService().install(candidate, false, result -> {
            int nextSuccess = successCount;
            if (result.outcome() == InstallStatus.INSTALLED || result.outcome() == InstallStatus.PENDING_RESTART) {
                nextSuccess++;
            }
            recordInstallOutcome(sender, queue, index, candidate, result, pendingRestartList, hotSwappedNow);
            installSequentially(sender, queue, index + 1, startTime, nextSuccess, pendingRestartList, skippedList, hotSwappedNow);
        });
    }

    void recordInstallOutcome(CommandSender sender, List<UpdateCandidate> queue, int index,
                              UpdateCandidate candidate, InstallResult result,
                              List<String> pendingRestartList, List<UpdateCandidate> hotSwappedNow) {
        Map<String, String> itemMap = placeholders(candidate);
        itemMap.put("index", String.valueOf(index + 1));
        itemMap.put("total", String.valueOf(queue.size()));
        itemMap.put("from", cleanVersion(result.fromVersion()));
        itemMap.put("to", cleanVersion(result.toVersion()));
        String detail = result.detail() == null || result.detail().isBlank() ? "—" : detailText(result.detail());
        itemMap.put("detail", detail);
        itemMap.put("error", detail);

        if (result.outcome() == InstallStatus.INSTALLED) {
            hotSwappedNow.add(candidate);
            sendAction(sender, "update.all-install-item-success", itemMap);
        } else if (result.outcome() == InstallStatus.PENDING_RESTART) {
            if (pendingRestartList != null) {
                pendingRestartList.add(candidate.identity().pluginName());
            }
            sendAction(sender, "update.all-install-item-pending", itemMap);
        } else {
            sendAction(sender, "update.all-install-item-failed", itemMap);
        }
    }

    private void reloadAffectedDependents(@Nullable List<UpdateCandidate> queue) {
        if (queue == null || queue.isEmpty()) return;

        Set<String> queueNames = new HashSet<>();
        for (UpdateCandidate c : queue) {
            if (c != null) {
                queueNames.add(c.identity().pluginName().toLowerCase(Locale.ROOT));
            }
        }

        DependencyGraph graphManager =
                plugin.getPluginLifecycleManager().getDependencyGraph();
        Map<String, DependencyNode> graph = graphManager.buildGraph(true);

        Set<String> affected = new HashSet<>();
        for (UpdateCandidate c : queue) {
            if (c == null) continue;
            String name = c.identity().pluginName();
            Set<String> deps = graphManager.getDependents(name, graph);
            for (String dep : deps) {
                if (!queueNames.contains(dep.toLowerCase(Locale.ROOT))) {
                    Plugin depPlugin = plugin.getPluginLifecycleManager().getPlugin(dep);
                    if (depPlugin != null && depPlugin.isEnabled() && !plugin.getPluginLifecycleManager().isProtected(depPlugin)) {
                        affected.add(dep);
                    }
                }
            }
        }

        if (affected.isEmpty()) {
            return;
        }

        List<String> sorted = plugin.getPluginLifecycleManager().getDependencyGraph().sortNamesTopologically(affected);
        Log.info("updatecommand.reloading-dependents", "count", String.valueOf(sorted.size()), "plugins", String.join(", ", sorted));
        for (String depName : sorted) {
            Plugin p = plugin.getPluginLifecycleManager().getPlugin(depName);
            if (p != null && p.isEnabled() && !plugin.getPluginLifecycleManager().isProtected(p)) {
                plugin.getPluginLifecycleManager().reload(p);
            }
        }
    }

    private Map<String, String> placeholders(UpdateCandidate candidate) {
        Map<String, String> map = new HashMap<>();
        map.put("cmd-type", "update");
        map.put("plugin", HexColors.escapeTags(candidate.identity().pluginName()));
        String currentVer = HexColors.escapeTags(cleanVersion(candidate.identity().currentVersion()));
        String latestVer = HexColors.escapeTags(cleanVersion(candidate.remoteVersionNumber()));
        map.put("current", currentVer);
        map.put("latest", latestVer);
        map.put("version", latestVer);
        String authors = HexColors.escapeTags(candidate.identity().authors() != null && !candidate.identity().authors().isEmpty()
                ? String.join(", ", candidate.identity().authors())
                : plugin.getConfigManager().text("actions.info.none-authors"));
        map.put("author", authors);
        String channelKey = candidate.version() != null && candidate.version().channel() != null
                ? candidate.version().channel().name().toLowerCase(Locale.ROOT)
                : "unknown";
        map.put("channel", formatChannel(channelKey));
        String sourceId = candidate.version() != null ? candidate.version().sourceId() : "unknown";
        map.put("source", formatSource(sourceId));
        map.put("reason", config().getString(candidate.reason().messageKey(), ""));
        String rawPageUrl = candidate.pageUrl() != null ? candidate.pageUrl() : "—";
        String rawDownloadUrl = candidate.version() != null && candidate.version().downloadUrl() != null && !candidate.version().downloadUrl().isBlank()
                ? candidate.version().downloadUrl()
                : rawPageUrl;

        map.put("raw-url", rawPageUrl);

        map.put("url", formatLink(rawPageUrl));
        map.put("file", candidate.version() != null && candidate.version().fileName() != null ? candidate.version().fileName() : "—");
        return map;
    }

    private @Nullable FileConfiguration config() {
        return plugin != null ? plugin.getConfigManager().getMessagesConfig() : null;
    }

    private String formatLink(@Nullable String url) {
        if (url == null || url.isBlank() || url.equals("—")) {
            return "—";
        }
        FileConfiguration config = config();
        List<String> hoverList = config != null ? config.getStringList("actions.update.link.hover") : Collections.emptyList();
        if (hoverList.isEmpty()) {
            if (config != null && config.isList("actions.link.hover")) {
                hoverList = config.getStringList("actions.link.hover");
            }
        }
        String rawHover;
        if (!hoverList.isEmpty()) {
            rawHover = String.join("\n", hoverList);
        } else {
            rawHover = "\n &#FFFF00◆ &fClick to &#FFFF00open &fthe link\n";
        }
        String miniHover = HexColors.toMiniMessage(rawHover);
        return "<click:open_url:\"" + url.replace("\"", "") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\"><underlined>" + url + "</underlined></hover></click>";
    }

    private String formatChannel(@Nullable String channelKey) {
        if (channelKey == null || channelKey.isBlank() || channelKey.equalsIgnoreCase("unknown")) {
            channelKey = "release";
        }
        FileConfiguration config = config();
        String val = config != null ? config.getString("actions.update.channels." + channelKey.toLowerCase(Locale.ROOT)) : null;
        if (val != null && !val.isBlank()) {
            return val;
        }
        return switch (channelKey.toLowerCase(Locale.ROOT)) {
            case "beta" -> "BETA";
            case "alpha" -> "ALPHA";
            default -> "RELEASE";
        };
    }

    private String formatSource(@Nullable String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            sourceId = "unknown";
        }
        FileConfiguration config = config();
        String val = config != null ? config.getString("actions.update.sources." + sourceId.toLowerCase(Locale.ROOT)) : null;
        if (val != null && !val.isBlank()) {
            return val;
        }
        if (UpdateSource.isPaidSource(sourceId)) {
            return UpdateSource.displayName(sourceId.replace("-premium", "")) + " (paid)";
        }
        return UpdateSource.displayName(sourceId);
    }

    private String cleanVersion(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        String trimmed = value.trim();
        String stripped = trimmed.replaceFirst("^[vV]+", "");
        return stripped.isEmpty() ? trimmed : stripped;
    }
}

