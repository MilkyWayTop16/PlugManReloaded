package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EnableCommand extends AbstractSubCommand {

    public EnableCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "enable";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.enable";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.argCount() < 2) {
            sendAction(sender, "help.enable");
            return true;
        }

        if (ctx.isAll()) {
            List<Plugin> disabled = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .filter(p -> !p.isEnabled())
                    .toList();
            if (disabled.isEmpty()) {
                sendAction(sender, "enable.all-empty");
                return true;
            }
            sendAction(sender, "enable.all-start");
            BulkOperationResult res = plugin.getPluginLifecycleManager().bulkEnable(disabled);
            sendBulkReport(sender, "enable", res);
            return true;
        }

        String targetName = ctx.target();
        Plugin targetPlugin = plugin.getPluginLifecycleManager().getPlugin(targetName);
        if (targetPlugin == null) {
            sendPluginNotFound(sender, targetName);
            return true;
        }

        if (isPluginLocked(sender, targetPlugin.getName(), "enable")) {
            return true;
        }

        if (targetPlugin.isEnabled()) {
            sendAction(sender, "errors.already-enabled", getPluginPlaceholders(targetPlugin));
            return true;
        }

        Map<String, String> startPh = getPluginPlaceholders(targetPlugin);
        sendAction(sender, "enable.start", startPh);
        long start = System.currentTimeMillis();

        PluginResult result = plugin.getPluginLifecycleManager().enable(targetPlugin);
        sendResult(sender, result, startPh, System.currentTimeMillis() - start);
        return true;
    }

    @Override
    public List<String> tabCandidates(int argLength, String previousToken, Set<String> usedTokens, CommandSender sender) {
        if (argLength == 2) {
            return withAllFlag(usedTokens, loadedPlugins(p -> !p.isEnabled()));
        }
        return Collections.emptyList();
    }
}

