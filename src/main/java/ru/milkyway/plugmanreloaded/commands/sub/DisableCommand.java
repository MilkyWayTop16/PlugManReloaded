package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.managers.SafetyManager;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DisableCommand extends AbstractSubCommand {

    public DisableCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "disable";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.disable";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.argCount() < 2) {
            sendAction(sender, "help.disable");
            return true;
        }

        if (ctx.isCancel()) {
            return handleCancel(ctx, "disable");
        }

        if (ctx.isAll()) {
            return disableAll(sender);
        }

        String targetName = ctx.target();
        Plugin targetPlugin = plugin.getPluginLifecycleManager().getPlugin(targetName);
        if (targetPlugin == null) {
            sendPluginNotFound(sender, targetName);
            return true;
        }

        if (checkProtected(sender, targetPlugin) || isPluginLocked(sender, targetPlugin.getName(), "disable")) {
            return true;
        }

        if (!targetPlugin.isEnabled()) {
            sendAction(sender, "errors.already-disabled", getPluginPlaceholders(targetPlugin));
            return true;
        }

        SafetyManager.SafetyAssessment assessment =
                plugin.getPluginLifecycleManager().getSafetyManager().assess(targetPlugin);
        if (assessment.riskLevel() == SafetyManager.PluginRiskLevel.CRITICAL_PROTECTED) {
            sendAction(sender, "errors.critical-protected", getPluginPlaceholders(targetPlugin));
            return true;
        }

        boolean force = ctx.hasFlag("f") || ctx.hasFlag("force");
        String token = ctx.token();
        if (token != null) {
            if (!plugin.getConfirmationManager().validateAndConsume(sender, "disable", targetName, token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
            force = true;
        } else if (force) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "disable", targetName);
        }

        Map<String, String> pluginPh = getPluginPlaceholders(targetPlugin);
        pluginPh.put("cmd-type", "disable");

        if (plugin.getConfigManager().isSafeModeEnabled() && !force && isRisky(assessment)) {
            return askRiskConfirmation(sender, targetPlugin, "disable", assessment, pluginPh);
        }

        sendAction(sender, "disable.start", pluginPh);
        long start = System.currentTimeMillis();
        PluginResult result = plugin.getPluginLifecycleManager().disable(targetPlugin);
        sendResult(sender, result, pluginPh, System.currentTimeMillis() - start);
        return true;
    }

    private boolean disableAll(CommandSender sender) {
        List<Plugin> enabled = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(p -> p.isEnabled() && !plugin.getPluginLifecycleManager().isProtected(p))
                .toList();
        if (enabled.isEmpty()) {
            sendAction(sender, "disable.all-empty");
            return true;
        }
        sendAction(sender, "disable.all-start");
        sendBulkReport(sender, "disable", plugin.getPluginLifecycleManager().bulkDisable(enabled));
        return true;
    }

    private static boolean isRisky(SafetyManager.SafetyAssessment assessment) {
        return !assessment.dependents().isEmpty()
                || assessment.riskLevel() == SafetyManager.PluginRiskLevel.API_PROVIDER
                || assessment.riskLevel() == SafetyManager.PluginRiskLevel.LOW_LEVEL_NETWORK;
    }

    @Override
    public List<String> tabCandidates(int argLength, String previousToken, Set<String> usedTokens, CommandSender sender) {
        if (argLength == 2) {
            LifecycleManager lifecycle = plugin != null ? plugin.getPluginLifecycleManager() : null;
            return withAllFlag(usedTokens, loadedPlugins(p -> p.isEnabled() && (lifecycle == null || !lifecycle.isProtected(p))));
        }
        return suggestFlags(usedTokens);
    }
}
