package ru.milkyway.plugmanreloaded.bridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.BrigadierManager;
import ru.milkyway.plugmanreloaded.managers.PluginCleanup;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;

public class FoliaBridge extends ModernPaperBridge {

    public FoliaBridge(PlugManReloaded plugin, BrigadierManager brigadierManager, PluginCleanup pluginCleanup) {
        super(plugin, brigadierManager, pluginCleanup);
    }

    @Override
    public PluginResult disablePlugin(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return PluginResult.ofError(FailureReason.INVALID_PLUGIN);
        cancelFoliaTasks(targetPlugin);
        return super.disablePlugin(targetPlugin);
    }

    @Override
    public PluginResult unloadPlugin(Plugin targetPlugin) {
        cancelFoliaTasks(targetPlugin);
        return super.unloadPlugin(targetPlugin);
    }

    private void cancelFoliaTasks(Plugin targetPlugin) {
        try {
            Object globalScheduler = ReflectionHelper.invokeMethod(Bukkit.getServer(), "getGlobalRegionScheduler");
            if (globalScheduler != null) {
                ReflectionHelper.invokeMethod(globalScheduler, "cancelTasks", targetPlugin);
            }
            Object asyncScheduler = ReflectionHelper.invokeMethod(Bukkit.getServer(), "getAsyncScheduler");
            if (asyncScheduler != null) {
                ReflectionHelper.invokeMethod(asyncScheduler, "cancelTasks", targetPlugin);
            }
        } catch (Throwable t) {
            Log.debug("foliabridge.cancel-tasks-failed", t, "plugin", targetPlugin.getName());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Object entityScheduler = ReflectionHelper.invokeMethod(player, "getScheduler");
                if (entityScheduler != null) {
                    ReflectionHelper.invokeMethod(entityScheduler, "cancelTasks", targetPlugin);
                }
            } catch (Throwable t) {
                Log.debug("foliabridge.cancel-entity-tasks-failed", t, "plugin", targetPlugin.getName(), "player", player.getName());
            }
        }
    }
}

