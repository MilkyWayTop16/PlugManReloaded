package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.UnloadSafetyChecker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

abstract class AbstractReloadCommand extends AbstractSubCommand {

    protected AbstractReloadCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    protected abstract String actionKey();

    protected abstract List<Plugin> bulkTargets();

    protected abstract BulkOperationResult runBulk(List<Plugin> plugins);

    protected abstract PluginResult runSingle(Plugin target);

    protected abstract PluginResult runCascade(Plugin target);

    protected boolean handleOwnFlags(CommandContext ctx) {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.argCount() < 2) {
            sendAction(sender, "help." + actionKey());
            return true;
        }

        if (ctx.isCancel()) {
            return handleCancel(ctx, actionKey());
        }

        if (handleOwnFlags(ctx)) {
            return true;
        }

        if (ctx.isAll()) {
            return handleAll(sender);
        }

        String targetName = ctx.target();
        Plugin targetPlugin = plugin.getPluginLifecycleManager().getPlugin(targetName);
        if (targetPlugin == null) {
            sendAction(sender, "errors.plugin-not-found", Map.of("plugin", targetName));
            return true;
        }

        if (checkProtected(sender, targetPlugin) || isPluginLocked(sender, targetPlugin.getName(), actionKey())) {
            return true;
        }

        if (!targetPlugin.isEnabled()) {
            sendAction(sender, "errors.reload-disabled", getPluginPlaceholders(targetPlugin));
            return true;
        }

        UnloadSafetyChecker.SafetyAssessment assessment =
                plugin.getPluginLifecycleManager().getSafetyAdvisor().assess(targetPlugin);
        if (assessment.riskLevel() == UnloadSafetyChecker.PluginRiskLevel.CRITICAL_PROTECTED) {
            sendAction(sender, "errors.critical-protected", getPluginPlaceholders(targetPlugin));
            return true;
        }

        boolean cascade = ctx.hasFlag("c") || ctx.hasFlag("cascade")
                || plugin.getConfigManager().isCascadeReloadByDefault();
        boolean force = ctx.hasFlag("f") || ctx.hasFlag("force");

        String token = ctx.token();
        if (token != null) {
            if (!plugin.getConfirmationManager().validateAndConsume(sender, actionKey(), targetName, token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
        } else if (force || cascade) {
            plugin.getConfirmationManager().consumeIfPresent(sender, actionKey(), targetName);
        }

        if (plugin.getConfigManager().isSafeModeEnabled() && !cascade && !force && isRisky(assessment)) {
            Map<String, String> placeholders = new HashMap<>(getPluginPlaceholders(targetPlugin));
            placeholders.put("dependents", riskReason(assessment));
            placeholders.put("token", plugin.getConfirmationManager()
                    .createSession(sender, actionKey(), targetPlugin.getName()));
            sendAction(sender, actionKey() + ".confirm", placeholders);
            return true;
        }

        if (cascade) {
            runCascadeAndReport(sender, targetPlugin);
        } else {
            runSingleAndReport(sender, targetPlugin);
        }
        return true;
    }

    private boolean handleAll(CommandSender sender) {
        List<Plugin> plugins = bulkTargets();
        if (plugins.isEmpty()) {
            sendAction(sender, actionKey() + ".all-empty");
            return true;
        }
        sendAction(sender, actionKey() + ".all-start");
        sendBulkReport(sender, actionKey(), runBulk(plugins));
        return true;
    }

    private static boolean isRisky(UnloadSafetyChecker.SafetyAssessment assessment) {
        return !assessment.dependents().isEmpty()
                || assessment.riskLevel() == UnloadSafetyChecker.PluginRiskLevel.LOW_LEVEL_NETWORK
                || assessment.riskLevel() == UnloadSafetyChecker.PluginRiskLevel.API_PROVIDER;
    }

    private String riskReason(UnloadSafetyChecker.SafetyAssessment assessment) {
        Set<String> dependents = assessment.dependents();
        return dependents.isEmpty()
                ? detailText(riskReasonKey("unload", assessment))
                : String.join(", ", dependents);
    }

    private void runCascadeAndReport(CommandSender sender, Plugin targetPlugin) {
        List<String> order = plugin.getPluginLifecycleManager().getDependencyGraph()
                .calculateCascadeOrder(targetPlugin.getName(), true);

        Map<String, String> startPlaceholders = new HashMap<>(getPluginPlaceholders(targetPlugin));
        startPlaceholders.put("count", String.valueOf(order.size()));
        sendAction(sender, "cascade-" + actionKey() + ".start", startPlaceholders);

        long start = System.currentTimeMillis();
        PluginResult result = runCascade(targetPlugin);
        sendResult(sender, result, getPluginPlaceholders(targetPlugin), System.currentTimeMillis() - start);
    }

    private void runSingleAndReport(CommandSender sender, Plugin targetPlugin) {
        sendAction(sender, actionKey() + ".start", getPluginPlaceholders(targetPlugin));

        long start = System.currentTimeMillis();
        PluginResult result = runSingle(targetPlugin);
        sendResult(sender, result, getPluginPlaceholders(targetPlugin), System.currentTimeMillis() - start);
    }
}
