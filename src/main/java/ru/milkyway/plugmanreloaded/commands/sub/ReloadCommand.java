package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.commands.CommandContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ReloadCommand extends AbstractReloadCommand {

    public ReloadCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public List<String> getAliases() {
        return List.of("rl");
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.reload";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected String actionKey() {
        return "reload";
    }

    @Override
    protected List<Plugin> bulkTargets() {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(p -> !plugin.getPluginLifecycleManager().isProtected(p) && p.isEnabled())
                .toList();
    }

    @Override
    protected BulkOperationResult runBulk(List<Plugin> plugins) {
        return plugin.getPluginLifecycleManager().bulkReload(plugins);
    }

    @Override
    protected PluginResult runSingle(Plugin target) {
        return plugin.getPluginLifecycleManager().reload(target);
    }

    @Override
    protected PluginResult runCascade(Plugin target) {
        return plugin.getPluginLifecycleManager().cascadeReload(target);
    }

    @Override
    protected boolean handleOwnFlags(CommandContext ctx) {
        boolean configReload = ctx.hasFlag("config") || (ctx.hasFlag("c") && !ctx.hasTarget());
        if (!configReload || ctx.isAll()) {
            return false;
        }

        CommandSender sender = ctx.sender();
        if (!sender.hasPermission("plugmanreloaded.config")) {
            sendAction(sender, "errors.no-permission");
            return true;
        }

        long start = System.currentTimeMillis();
        plugin.getConfigManager().reload();
        plugin.getUpdateChecker().reload();
        long elapsed = System.currentTimeMillis() - start;

        sendAction(sender, "config-reload.success", Map.of(
                "time", String.valueOf(elapsed),
                "ms", String.valueOf(elapsed)
        ));
        return true;
    }
}
