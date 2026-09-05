package ru.milkyway.plugmanreloaded.managers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BrigadierManager {

    private final PlugManReloaded plugin;
    private MethodHandle syncCommandsHandle;
    private final AtomicBoolean pendingSync = new AtomicBoolean(false);

    public BrigadierManager(PlugManReloaded plugin) {
        this.plugin = plugin;
        initSyncCommandsHandle();
    }

    private void initSyncCommandsHandle() {
        if (Bukkit.getServer() == null) return;
        try {
            Class<?> craftServerClass = Bukkit.getServer().getClass();
            Method syncMethod = craftServerClass.getDeclaredMethod("syncCommands");
            syncMethod.setAccessible(true);
            this.syncCommandsHandle = MethodHandles.lookup().unreflect(syncMethod);
        } catch (Throwable t) {
            this.syncCommandsHandle = null;
            Log.debug("brigadiermanager.methodhandle-unavailable", t);
        }
    }

    public void syncCommands() {
        if (plugin == null || plugin.getConfigManager() == null || !plugin.getConfigManager().isAutoSyncCommands()) return;

        if (pendingSync.compareAndSet(false, true)) {
            TaskScheduler.runSync(plugin, () -> {
                pendingSync.set(false);
                performSyncCommands();
            });
        }
    }

    private void performSyncCommands() {
        if (Bukkit.getServer() == null) return;

        if (syncCommandsHandle != null) {
            try {
                syncCommandsHandle.invoke(Bukkit.getServer());
                return;
            } catch (Throwable t) {
                Log.debug("brigadiermanager.sync-methodhandle-failed", t);
            }
        }

        try {
            ReflectionHelper.invokeMethod(Bukkit.getServer(), "syncCommands");
        } catch (Throwable t) {
            Log.debug("brigadiermanager.sync-reflection-failed", t);
        }
    }

    @SuppressWarnings("unchecked")
    public void unregisterPluginCommands(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null || Bukkit.getServer() == null) return;
        String pluginPrefix = targetPlugin.getName().toLowerCase(Locale.ROOT) + ":";

        try {
            SimpleCommandMap commandMap = ReflectionHelper.getFieldValue(Bukkit.getServer(), "commandMap");
            if (commandMap == null) return;

            synchronized (commandMap) {
                Map<String, Command> knownCommands = ReflectionHelper.getFieldValue(SimpleCommandMap.class, commandMap, "knownCommands");
                if (knownCommands == null) return;

                List<String> removedLabels;
                synchronized (knownCommands) {
                    removedLabels = removeMatchingCommands(knownCommands, targetPlugin, pluginPrefix, commandMap);
                }

                cleanBrigadierDispatcher(targetPlugin, removedLabels);
            }
        } catch (Throwable t) {
            Log.debug("brigadiermanager.cleanup-error", t, "plugin", targetPlugin.getName());
        }
    }

    static List<String> removeMatchingCommands(Map<String, Command> knownCommands, Plugin targetPlugin,
                                                String pluginPrefix, SimpleCommandMap commandMap) {
        Set<String> toRemove = new LinkedHashSet<>();
        if (targetPlugin != null && targetPlugin.getDescription() != null && targetPlugin.getDescription().getCommands() != null) {
            for (String name : targetPlugin.getDescription().getCommands().keySet()) {
                if (name != null) {
                    toRemove.add(name.toLowerCase(Locale.ROOT));
                    toRemove.add(pluginPrefix + name.toLowerCase(Locale.ROOT));
                }
            }
        }

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            String cmdLabel = entry.getKey();
            Command cmd = entry.getValue();

            boolean match = false;
            if (cmd instanceof PluginCommand pc) {
                if (targetPlugin.equals(pc.getPlugin())) {
                    match = true;
                }
            } else if (cmdLabel.toLowerCase(Locale.ROOT).startsWith(pluginPrefix)) {
                match = true;
            }

            if (match) {
                toRemove.add(cmdLabel);
            }
        }

        for (String label : toRemove) {
            Command removed = knownCommands.remove(label);
            if (removed != null) {
                try {
                    removed.unregister(commandMap);
                } catch (Throwable t) {
                    Log.debug("brigadiermanager.unregister-failed", t, "command", label, "plugin", targetPlugin.getName());
                }
            }
        }

        return new ArrayList<>(toRemove);
    }

    @SuppressWarnings("unchecked")
    private void cleanBrigadierDispatcher(Plugin targetPlugin, @Nullable List<String> commandNames) {
        if (commandNames == null || commandNames.isEmpty()) return;

        try {
            Object console = ReflectionHelper.getFieldValue(Bukkit.getServer(), "console");
            if (console == null) return;

            Object vanillaCommands = ReflectionHelper.getFieldValue(console, "vanillaCommandDispatcher");
            if (vanillaCommands == null) {
                vanillaCommands = ReflectionHelper.getFieldValue(console, "commands");
            }
            if (vanillaCommands == null) {
                vanillaCommands = ReflectionHelper.invokeMethod(console, "getCommands");
            }
            if (vanillaCommands == null) {
                Object resources = ReflectionHelper.getFieldValue(console, "resources");
                if (resources != null) {
                    Object managers = ReflectionHelper.invokeMethod(resources, "managers");
                    if (managers != null) {
                        vanillaCommands = ReflectionHelper.invokeMethod(managers, "commands");
                    }
                }
            }
            if (vanillaCommands == null) return;

            Object rawDispatcher = ReflectionHelper.getFieldValue(vanillaCommands, "dispatcher");
            if (!(rawDispatcher instanceof CommandDispatcher)) {
                rawDispatcher = ReflectionHelper.getFieldValueOfType(vanillaCommands, CommandDispatcher.class);
            }
            if (!(rawDispatcher instanceof CommandDispatcher)) {
                rawDispatcher = ReflectionHelper.invokeMethod(vanillaCommands, "getDispatcher");
            }
            if (!(rawDispatcher instanceof CommandDispatcher)) return;

            @SuppressWarnings("unchecked")
            CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>) rawDispatcher;

            RootCommandNode<Object> root = dispatcher.getRoot();
            if (root == null) return;

            synchronized (root) {
                Map<String, CommandNode<Object>> children = ReflectionHelper.getFieldValue(CommandNode.class, root, "children");
                Map<String, CommandNode<Object>> literals = ReflectionHelper.getFieldValue(CommandNode.class, root, "literals");

                if (children != null) {
                    synchronized (children) {
                        for (String name : commandNames) {
                            String cleanName = name.contains(":") ? name.substring(name.indexOf(":") + 1) : name;
                            children.remove(name.toLowerCase(Locale.ROOT));
                            children.remove(cleanName.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                if (literals != null) {
                    synchronized (literals) {
                        for (String name : commandNames) {
                            String cleanName = name.contains(":") ? name.substring(name.indexOf(":") + 1) : name;
                            literals.remove(name.toLowerCase(Locale.ROOT));
                            literals.remove(cleanName.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("brigadiermanager.nodes-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }
}

