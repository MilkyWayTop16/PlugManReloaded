package ru.milkyway.plugmanreloaded.managers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
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

        if (!plugin.isEnabled()) {
            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                performSyncCommands();
            }
            return;
        }

        if (pendingSync.compareAndSet(false, true)) {
            TaskScheduler.runSyncLater(plugin, () -> {
                pendingSync.set(false);
                performSyncCommands();
            }, 1L);
        }
    }

    private void performSyncCommands() {
        if (Bukkit.getServer() == null) return;

        if (syncCommandsHandle == null) {
            initSyncCommandsHandle();
        }

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

            List<String> removedLabels;
            synchronized (commandMap) {
                Map<String, Command> knownCommands = ReflectionHelper.getFieldValue(SimpleCommandMap.class, commandMap, "knownCommands");
                if (knownCommands == null) return;

                synchronized (knownCommands) {
                    removedLabels = removeMatchingCommands(knownCommands, targetPlugin, pluginPrefix, commandMap);
                }
            }

            cleanBrigadierDispatcher(targetPlugin, removedLabels);
        } catch (Throwable t) {
            Log.debug("brigadiermanager.cleanup-error", t, "plugin", targetPlugin.getName());
        }
    }

    static List<String> removeMatchingCommands(Map<String, Command> knownCommands, Plugin targetPlugin,
                                                String pluginPrefix, SimpleCommandMap commandMap) {
        Set<String> toRemove = new LinkedHashSet<>();
        if (targetPlugin != null && targetPlugin.getDescription() != null && targetPlugin.getDescription().getCommands() != null) {
            for (Map.Entry<String, Map<String, Object>> entry : targetPlugin.getDescription().getCommands().entrySet()) {
                String name = entry.getKey();
                if (name != null) {
                    toRemove.add(name);
                    toRemove.add(name.toLowerCase(Locale.ROOT));
                    toRemove.add(pluginPrefix + name);
                    toRemove.add(pluginPrefix + name.toLowerCase(Locale.ROOT));
                }
                Map<String, Object> details = entry.getValue();
                if (details != null && details.get("aliases") instanceof List<?> aliases) {
                    for (Object alias : aliases) {
                        if (alias instanceof String aliasStr) {
                            toRemove.add(aliasStr);
                            toRemove.add(aliasStr.toLowerCase(Locale.ROOT));
                            toRemove.add(pluginPrefix + aliasStr);
                            toRemove.add(pluginPrefix + aliasStr.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }

        ClassLoader targetCl = (targetPlugin != null) ? targetPlugin.getClass().getClassLoader() : null;

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            String cmdLabel = entry.getKey();
            Command cmd = entry.getValue();

            boolean match = false;
            if (cmd instanceof PluginCommand pc) {
                if (targetPlugin != null && targetPlugin.equals(pc.getPlugin())) {
                    match = true;
                }
            } else if (cmd instanceof PluginIdentifiableCommand pic) {
                if (targetPlugin != null && targetPlugin.equals(pic.getPlugin())) {
                    match = true;
                }
            } else if (cmdLabel != null && cmdLabel.toLowerCase(Locale.ROOT).startsWith(pluginPrefix)) {
                match = true;
            } else if (cmd != null && targetCl != null && cmd.getClass().getClassLoader() == targetCl) {
                match = true;
            }

            if (match && cmdLabel != null) {
                toRemove.add(cmdLabel);
            }
        }

        for (String label : toRemove) {
            Command removed = knownCommands.remove(label);
            if (removed == null) {
                removed = knownCommands.remove(label.toLowerCase(Locale.ROOT));
            }
            if (removed != null) {
                try {
                    removed.unregister(commandMap);
                } catch (Throwable t) {
                    Log.debug("brigadiermanager.unregister-failed", t, "command", label, "plugin", targetPlugin != null ? targetPlugin.getName() : "unknown");
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

            Set<String> cleanLabels = new HashSet<>();
            for (String name : commandNames) {
                if (name == null || name.isBlank()) continue;
                cleanLabels.add(name);
                cleanLabels.add(name.toLowerCase(Locale.ROOT));
                if (name.contains(":")) {
                    String sub = name.substring(name.indexOf(":") + 1);
                    cleanLabels.add(sub);
                    cleanLabels.add(sub.toLowerCase(Locale.ROOT));
                }
            }

            synchronized (dispatcher) {
                synchronized (root) {
                    Map<String, CommandNode<Object>> children = ReflectionHelper.getFieldValue(CommandNode.class, root, "children");
                    Map<String, CommandNode<Object>> literals = ReflectionHelper.getFieldValue(CommandNode.class, root, "literals");
                    Map<String, CommandNode<Object>> arguments = ReflectionHelper.getFieldValue(CommandNode.class, root, "arguments");

                    if (children != null) {
                        synchronized (children) {
                            for (String label : cleanLabels) {
                                children.remove(label);
                            }
                            children.keySet().removeIf(k -> {
                                for (String label : cleanLabels) {
                                    if (k.equalsIgnoreCase(label)) return true;
                                }
                                return false;
                            });
                        }
                    }
                    if (literals != null) {
                        synchronized (literals) {
                            for (String label : cleanLabels) {
                                literals.remove(label);
                            }
                            literals.keySet().removeIf(k -> {
                                for (String label : cleanLabels) {
                                    if (k.equalsIgnoreCase(label)) return true;
                                }
                                return false;
                            });
                        }
                    }
                    if (arguments != null) {
                        synchronized (arguments) {
                            for (String label : cleanLabels) {
                                arguments.remove(label);
                            }
                            arguments.keySet().removeIf(k -> {
                                for (String label : cleanLabels) {
                                    if (k.equalsIgnoreCase(label)) return true;
                                }
                                return false;
                            });
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("brigadiermanager.nodes-cleanup-failed", t, "plugin", targetPlugin != null ? targetPlugin.getName() : "unknown");
        }
    }
}

