package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Player;
import org.bukkit.help.HelpMap;
import org.bukkit.help.HelpTopic;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.Recipe;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServerStateCleanup {

    private ServerStateCleanup() {}

    public static void cleanupAll(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null || Bukkit.getServer() == null) return;
        ClassLoader targetCl = targetPlugin.getClass().getClassLoader();

        closePluginInventories(targetPlugin, targetCl);

        cleanPermissionAttachments(targetPlugin);

        cleanPermissions(targetPlugin);

        cleanMetadata(targetPlugin);

        cleanRecipes(targetPlugin);

        cleanBossBars(targetPlugin);

        cleanHelpTopics(targetPlugin);
    }

    public static void closeAllOnlineInventories() {
        if (Bukkit.getServer() == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) continue;
            TaskScheduler.runForEntity(plugin, player, () -> {
                try {
                    if (player.isOnline()) {
                        player.closeInventory();
                    }
                } catch (Throwable t) {
                    Log.debug("serverstatecleanup.inventory-close-error-global", t, "player", player.getName());
                }
            });
        }
    }

    private static void closePluginInventories(Plugin targetPlugin, @Nullable ClassLoader targetCl) {
        if (targetCl == null || Bukkit.getServer() == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        AtomicInteger failed = new AtomicInteger();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) continue;
            TaskScheduler.runForEntity(plugin, player, () -> {
                try {
                    if (!player.isOnline()) return;
                    org.bukkit.inventory.InventoryView view = player.getOpenInventory();
                    if (view == null) return;
                    Inventory top = view.getTopInventory();
                    if (top == null) return;
                    InventoryHolder holder = top.getHolder();
                    if (holder != null && holder.getClass().getClassLoader() == targetCl) {
                        player.closeInventory();
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    Log.debug("serverstatecleanup.inventory-close-error", t, "player", player.getName());
                }
            });
        }
        if (failed.get() > 0) {
            Log.debug("serverstatecleanup.inventory-close-failed-count", "count", String.valueOf(failed.get()), "plugin", targetPlugin.getName());
        }
    }

    private static void cleanPermissionAttachments(Plugin targetPlugin) {
        if (targetPlugin == null || Bukkit.getServer() == null) return;
        PlugManReloaded plugin = PlugManReloaded.getInstance();
        AtomicInteger failed = new AtomicInteger();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) continue;
            TaskScheduler.runForEntity(plugin, player, () -> {
                try {
                    if (!player.isOnline()) return;
                    List<PermissionAttachment> toRemove = new ArrayList<>();
                    for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                        PermissionAttachment attachment = info.getAttachment();
                        if (attachment != null && targetPlugin.equals(attachment.getPlugin())) {
                            toRemove.add(attachment);
                        }
                    }
                    for (PermissionAttachment att : toRemove) {
                        player.removeAttachment(att);
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    Log.debug("serverstatecleanup.attachment-cleanup-error", t, "player", player.getName());
                }
            });
        }
        if (failed.get() > 0) {
            Log.debug("serverstatecleanup.attachment-cleanup-failed-count", "count", String.valueOf(failed.get()), "plugin", targetPlugin.getName());
        }
    }

    static Set<String> permissionPrefixesFor(String pluginName, List<String> provides) {
        Set<String> prefixes = new HashSet<>();
        if (pluginName == null || pluginName.isBlank()) {
            return prefixes;
        }
        String nameLower = pluginName.toLowerCase(Locale.ROOT);
        prefixes.add(nameLower + ".");

        String dotted = nameLower.replace('-', '.').replace('_', '.');
        if (!dotted.equals(nameLower)) {
            prefixes.add(dotted + ".");
        }

        if (provides != null) {
            for (String prov : provides) {
                if (prov != null && !prov.isBlank()) {
                    prefixes.add(prov.toLowerCase(Locale.ROOT) + ".");
                }
            }
        }
        return prefixes;
    }

    private static void cleanPermissions(Plugin targetPlugin) {
        try {
            Set<String> prefixes = permissionPrefixesFor(
                    targetPlugin.getName(), targetPlugin.getDescription().getProvides());

            List<String> permNames = PluginMetaHelper.getPermissionNames(targetPlugin);
            for (String permName : permNames) {
                try {
                    Bukkit.getPluginManager().removePermission(permName);
                } catch (Throwable t) {
                    Log.debug("serverstatecleanup.permission-remove-failed", t, "permission", permName);
                }
            }

            Set<Permission> allPerms = Bukkit.getPluginManager().getPermissions();
            if (allPerms != null) {
                List<Permission> toRemove = new ArrayList<>();
                for (Permission perm : allPerms) {
                    if (perm == null || perm.getName() == null) continue;
                    String permLower = perm.getName().toLowerCase(Locale.ROOT);
                    for (String pfx : prefixes) {
                        if (permLower.startsWith(pfx)) {
                            toRemove.add(perm);
                            break;
                        }
                    }
                }
                for (Permission perm : toRemove) {
                    try {
                        Bukkit.getPluginManager().removePermission(perm);
                    } catch (Throwable t) {
                        Log.debug("serverstatecleanup.dynamic-permission-remove-failed", t, "permission", perm.getName());
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("serverstatecleanup.permissions-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private static void cleanMetadata(Plugin targetPlugin) {
        try {
            Class<?> craftServerClass = Bukkit.getServer().getClass();
            String[] storeFields = {"entityMetadata", "playerMetadata", "worldMetadata", "blockMetadata"};
            for (String fieldName : storeFields) {
                Object store = ReflectionHelper.getFieldValue(craftServerClass, Bukkit.getServer(), fieldName);
                if (store != null) {
                    ReflectionHelper.invokeMethod(store, "removeAll", targetPlugin);
                }
            }
        } catch (Throwable t) {
            Log.debug("serverstatecleanup.metadata-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private static void cleanRecipes(Plugin targetPlugin) {
        String namespace = targetPlugin.getName().toLowerCase(Locale.ROOT);
        try {
            Object server = ReflectionHelper.invokeMethod(Bukkit.getServer(), "getServer");
            if (server != null) {
                Object recipeManager = ReflectionHelper.invokeMethod(server, "getRecipeManager");
                if (recipeManager != null) {
                    Map<?, ?> byName = ReflectionHelper.getFieldValue(recipeManager.getClass(), recipeManager, "byName");
                    if (byName != null) {
                        List<NamespacedKey> matchingKeys = new ArrayList<>();
                        for (Object keyObj : byName.keySet()) {
                            if (keyObj != null) {
                                String keyStr = keyObj.toString();
                                int colon = keyStr.indexOf(':');
                                String keyNamespace = colon > 0 ? keyStr.substring(0, colon) : keyStr;
                                if (keyNamespace.equalsIgnoreCase(namespace)) {
                                    NamespacedKey nk = NamespacedKey.fromString(keyStr);
                                    if (nk != null) {
                                        matchingKeys.add(nk);
                                    }
                                }
                            }
                        }
                        for (NamespacedKey key : matchingKeys) {
                            Bukkit.removeRecipe(key);
                        }
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe recipe = it.next();
                if (recipe instanceof Keyed keyed) {
                    if (keyed.getKey().getNamespace().equalsIgnoreCase(namespace)) {
                        it.remove();
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("serverstatecleanup.recipes-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private static void cleanBossBars(Plugin targetPlugin) {
        try {
            String namespace = targetPlugin.getName().toLowerCase(Locale.ROOT);
            Iterator<KeyedBossBar> it = Bukkit.getBossBars();
            List<NamespacedKey> toRemove = new ArrayList<>();
            while (it.hasNext()) {
                KeyedBossBar bar = it.next();
                if (bar.getKey().getNamespace().equalsIgnoreCase(namespace)) {
                    bar.removeAll();
                    toRemove.add(bar.getKey());
                }
            }
            for (NamespacedKey key : toRemove) {
                Bukkit.removeBossBar(key);
            }
        } catch (Throwable t) {
            Log.debug("serverstatecleanup.bossbars-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void cleanHelpTopics(Plugin targetPlugin) {
        try {
            HelpMap helpMap = Bukkit.getHelpMap();
            if (helpMap == null) return;

            Map<String, HelpTopic> helpTopics = ReflectionHelper.getFieldValue(helpMap, "helpTopics");
            if (helpTopics != null) {
                synchronized (helpTopics) {
                    String prefix = targetPlugin.getName().toLowerCase(Locale.ROOT) + ":";
                    helpTopics.entrySet().removeIf(entry -> {
                        HelpTopic topic = entry.getValue();
                        if (topic == null || entry.getKey() == null) return false;
                        if (entry.getKey().toLowerCase(Locale.ROOT).startsWith(prefix)) return true;
                        Plugin p = ReflectionHelper.getFieldValue(topic, "plugin");
                        return targetPlugin.equals(p);
                    });
                }
            }
        } catch (Throwable t) {
            Log.debug("serverstatecleanup.helptopics-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }
}

