package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.UnloadSafetyChecker;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class UnloadCommand extends AbstractSubCommand {

    public UnloadCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "unload";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.unload";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.argCount() < 2) {
            sendAction(sender, "help.unload");
            return true;
        }

        if (ctx.isCancel()) {
            return handleCancel(ctx, "unload");
        }

        if (ctx.isAll()) {
            return unloadAll(sender);
        }

        String targetName = ctx.target();
        Plugin targetPlugin = plugin.getPluginLifecycleManager().getPlugin(targetName);
        if (targetPlugin == null) {
            sendAction(sender, "errors.plugin-not-found", Map.of("plugin", targetName));
            return true;
        }

        if (checkProtected(sender, targetPlugin) || isPluginLocked(sender, targetPlugin.getName(), "unload")) {
            return true;
        }

        UnloadSafetyChecker.SafetyAssessment assessment =
                plugin.getPluginLifecycleManager().getSafetyAdvisor().assess(targetPlugin);
        if (assessment.riskLevel() == UnloadSafetyChecker.PluginRiskLevel.CRITICAL_PROTECTED) {
            sendAction(sender, "errors.critical-protected", getPluginPlaceholders(targetPlugin));
            return true;
        }

        boolean force = ctx.hasFlag("f") || ctx.hasFlag("force");
        String token = ctx.token();
        if (token != null) {
            if (!plugin.getConfirmationManager().validateAndConsume(sender, "unload", targetName, token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
            force = true;
        } else if (force) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "unload", targetName);
        }

        Map<String, String> pluginPh = getPluginPlaceholders(targetPlugin);
        pluginPh.put("cmd-type", "unload");

        boolean risky = assessment.riskLevel() != UnloadSafetyChecker.PluginRiskLevel.SAFE;
        if (plugin.getConfigManager().isSafeModeEnabled() && !force && risky) {
            return askRiskConfirmation(sender, targetPlugin, "unload", assessment, pluginPh);
        }

        sendAction(sender, "unload.start", pluginPh);
        long start = System.currentTimeMillis();
        PluginResult result = plugin.getPluginLifecycleManager().unload(targetPlugin);
        sendResult(sender, result, pluginPh, System.currentTimeMillis() - start);
        return true;
    }

    private boolean unloadAll(CommandSender sender) {
        List<Plugin> plugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(p -> !plugin.getPluginLifecycleManager().isProtected(p))
                .toList();
        if (plugins.isEmpty()) {
            sendAction(sender, "unload.all-empty");
            return true;
        }
        sendAction(sender, "unload.all-start");
        sendBulkReport(sender, "unload", plugin.getPluginLifecycleManager().bulkUnload(plugins));
        return true;
    }
}
