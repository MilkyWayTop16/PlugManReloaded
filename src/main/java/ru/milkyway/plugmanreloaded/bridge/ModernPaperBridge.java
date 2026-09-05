package ru.milkyway.plugmanreloaded.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.BrigadierManager;
import ru.milkyway.plugmanreloaded.managers.PluginCleanup;
import ru.milkyway.plugmanreloaded.utils.ErrorAnalyzer;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ModernPaperBridge extends LegacyBukkitBridge {

    public ModernPaperBridge(PlugManReloaded plugin, BrigadierManager brigadierManager, PluginCleanup pluginCleanup) {
        super(plugin, brigadierManager, pluginCleanup);
    }

    @Override
    protected void postUnloadCleanup(Plugin targetPlugin) {
        cleanPaperPluginManager(targetPlugin);
    }

    @Override
    public PluginResult reloadPlugin(Plugin targetPlugin) {
        PluginResult res = restartPlugin(targetPlugin);
        if (res.success()) {
            return PluginResult.ofSuccess("reload.success", res.placeholders());
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private void cleanPaperPluginManager(Plugin targetPlugin) {
        try {
            Class<?> paperPluginManagerImplClass = ReflectionHelper.getClass("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            if (paperPluginManagerImplClass == null) return;

            Object paperManagerInstance = ReflectionHelper.invokeStaticMethod(paperPluginManagerImplClass, "getInstance");
            if (paperManagerInstance == null) return;

            Object instanceManager = ReflectionHelper.getFieldValue(paperManagerInstance, "instanceManager");
            if (instanceManager == null) return;

            String name = targetPlugin.getName();
            String lowerName = name.toLowerCase(Locale.ROOT);

            Map<String, ?> lookupNames = ReflectionHelper.getFieldValue(instanceManager, "lookupNames");
            if (lookupNames != null) {
                synchronized (lookupNames) {
                    lookupNames.remove(lowerName);
                    lookupNames.remove(name);
                }
            }

            List<?> plugins = ReflectionHelper.getFieldValue(instanceManager, "plugins");
            if (plugins != null) {
                synchronized (plugins) {
                    plugins.remove(targetPlugin);
                }
            }

            Object dependencyTree = ReflectionHelper.getFieldValue(instanceManager, "dependencyTree");
            if (dependencyTree != null) {
                Object pluginMeta = ReflectionHelper.invokeMethod(targetPlugin, "getPluginMeta");
                if (pluginMeta != null) {
                    ReflectionHelper.invokeMethod(dependencyTree, "remove", pluginMeta);
                }

                Set<?> dependencies = ReflectionHelper.getFieldValue(dependencyTree, "dependencies");
                if (dependencies != null) {
                    dependencies.remove(lowerName);
                    dependencies.remove(name);
                }

                Object graph = ReflectionHelper.getFieldValue(dependencyTree, "graph");
                if (graph != null) {
                    ReflectionHelper.invokeMethod(graph, "removeNode", name);
                }
            }
        } catch (Throwable t) {
            Log.debug("modernpaperbridge.paperpluginmanager-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }
}

