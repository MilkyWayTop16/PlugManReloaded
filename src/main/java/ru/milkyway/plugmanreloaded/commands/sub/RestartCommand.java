package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class RestartCommand extends AbstractReloadCommand {

    public RestartCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "restart";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.restart";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected String actionKey() {
        return "restart";
    }

    @Override
    protected List<Plugin> bulkTargets() {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(p -> !plugin.getPluginLifecycleManager().isProtected(p))
                .toList();
    }

    @Override
    protected BulkOperationResult runBulk(List<Plugin> plugins) {
        return plugin.getPluginLifecycleManager().bulkRestart(plugins);
    }

    @Override
    protected PluginResult runSingle(Plugin target) {
        return plugin.getPluginLifecycleManager().restart(target);
    }

    @Override
    protected PluginResult runCascade(Plugin target) {
        return plugin.getPluginLifecycleManager().cascadeRestart(target);
    }

    @Override
    public List<String> tabCandidates(int argLength, String previousToken, Set<String> usedTokens, CommandSender sender) {
        if (argLength == 2) {
            LifecycleManager lifecycle = plugin != null ? plugin.getPluginLifecycleManager() : null;
            return withAllFlag(usedTokens, loadedPlugins(p -> lifecycle == null || !lifecycle.isProtected(p)));
        }
        return suggestFlags(usedTokens);
    }
}
