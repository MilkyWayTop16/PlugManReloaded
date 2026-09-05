package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.PluginResult;

import java.util.Arrays;
import java.util.List;

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
}
