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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            sendAction(sender, "errors.plugin-not-found", Map.of("plugin", targetName));
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
        long elapsed = System.currentTimeMillis() - start;

        Map<String, String> placeholders = new HashMap<>(startPh);
        placeholders.putAll(result.placeholders());
        placeholders.put("time", String.valueOf(elapsed));

        sendAction(sender, result.messageKey(), placeholders);
        return true;
    }
}

