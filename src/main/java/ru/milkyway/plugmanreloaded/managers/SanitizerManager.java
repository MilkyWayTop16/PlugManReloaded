package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.help.HelpMap;
import org.bukkit.help.HelpTopic;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.Recipe;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.MetaspaceCleanup;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.beans.Introspector;
import java.io.Closeable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SanitizerManager {

    private final PlugManReloaded plugin;

    public SanitizerManager(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    public void cleanup(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return;
        ClassLoader classLoader = targetPlugin.getClass().getClassLoader();

        boolean isSelf = (plugin != null && (classLoader == plugin.getClass().getClassLoader() || targetPlugin.equals(plugin) || targetPlugin.getName().equalsIgnoreCase(plugin.getName())))
                || classLoader == getClass().getClassLoader()
                || targetPlugin.getName().equalsIgnoreCase("PlugManReloaded");
        if (isSelf) {
            Log.warn("plugincleanup.self-blocked", "plugin", targetPlugin.getName());
            return;
        }

        if (Bukkit.getServer() != null) {
            try {
                Bukkit.getServicesManager().unregisterAll(targetPlugin);
            } catch (Throwable t) {
                Log.debug("plugincleanup.services-failed", t, "plugin", targetPlugin.getName());
            }
        }

        try {
            HandlerList.unregisterAll(targetPlugin);
            cleanInternalListeners(targetPlugin);
        } catch (Throwable t) {
            Log.debug("plugincleanup.listeners-failed", t, "plugin", targetPlugin.getName());
        }

        if (Bukkit.getServer() != null && Bukkit.getMessenger() != null) {
            try {
                Bukkit.getMessenger().unregisterIncomingPluginChannel(targetPlugin);
                Bukkit.getMessenger().unregisterOutgoingPluginChannel(targetPlugin);
            } catch (Throwable t) {
                Log.debug("plugincleanup.messenger-failed", t, "plugin", targetPlugin.getName());
            }
        }

        if (!PlatformDetector.isFolia() && Bukkit.getServer() != null) {
            try {
                Bukkit.getScheduler().cancelTasks(targetPlugin);
            } catch (Throwable t) {
                Log.debug("plugincleanup.scheduler-failed", t, "plugin", targetPlugin.getName());
            }
        }

        cleanPlaceholderAPI(targetPlugin);

        cleanPacketLibraries(targetPlugin);
        NettyGuard.cleanPlayerPipelines(targetPlugin);

        cleanupServerState(targetPlugin);

        cleanThreads(targetPlugin, classLoader);

        cleanJdbcDrivers(targetPlugin, classLoader);

        cleanPaperLifecycle(targetPlugin);

        cleanPaperDependencies(targetPlugin, classLoader);

        cleanStaticFields(targetPlugin);

        try {
            Introspector.flushCaches();
        } catch (Throwable t) {
            Log.debug("plugincleanup.introspector-failed", t, "plugin", targetPlugin.getName());
        }

        try {
            ReflectionHelper.purgeClassLoader(classLoader);
        } catch (Throwable t) {
            Log.debug("plugincleanup.reflection-cache-failed", t, "plugin", targetPlugin.getName());
        }

        closeClassLoader(targetPlugin, classLoader);
    }

    @SuppressWarnings("unchecked")
    private void cleanInternalListeners(Plugin targetPlugin) {
        if (Bukkit.getServer() == null) return;
        PluginManager pm = Bukkit.getPluginManager();
        if (pm instanceof SimplePluginManager spm) {
            Map<?, ?> listeners = ReflectionHelper.getFieldValue(SimplePluginManager.class, spm, "listeners");
            purgeListenersMap(listeners, targetPlugin);

            Object paperEventManager = ReflectionHelper.getFieldValue(spm, "paperEventManager");
            if (paperEventManager != null) {
                try {
                    ReflectionHelper.invokeMethod(paperEventManager, "unregister", targetPlugin);
                } catch (Throwable t) {
                    Log.debug("plugincleanup.paper-event-manager-failed", t, "plugin", targetPlugin.getName());
                }
            }
        }
    }

    static void purgeListenersMap(@Nullable Map<?, ?> listeners, Plugin targetPlugin) {
        if (listeners == null) return;
        for (Object val : listeners.values()) {
            if (val instanceof Collection<?> collection) {
                collection.removeIf(item -> isListenerOf(item, targetPlugin));
            } else if (val instanceof Map<?, ?> mapVal) {
                for (Object subVal : mapVal.values()) {
                    if (subVal instanceof Collection<?> subCollection) {
                        subCollection.removeIf(item -> isListenerOf(item, targetPlugin));
                    }
                }
            }
        }
    }

    private static boolean isListenerOf(Object item, Plugin targetPlugin) {
        if (item instanceof RegisteredListener rl) {
            return targetPlugin.equals(rl.getPlugin());
        }
        Plugin p = ReflectionHelper.getFieldValue(item, "plugin");
        return targetPlugin.equals(p);
    }

    private void cleanPlaceholderAPI(Plugin targetPlugin) {
        if (Bukkit.getServer() == null || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            Class<?> papiClass = ReflectionHelper.getClass("me.clip.placeholderapi.PlaceholderAPI");
            if (papiClass != null) {
                ReflectionHelper.invokeStaticMethod(papiClass, "unregisterExpansion", targetPlugin);
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.placeholderapi-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void cleanPacketLibraries(Plugin targetPlugin) {
        if (Bukkit.getServer() == null) return;
        if (Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            try {
                Class<?> protocolLibrary = ReflectionHelper.getClass("com.comphenix.protocol.ProtocolLibrary");
                if (protocolLibrary != null) {
                    Object manager = ReflectionHelper.invokeStaticMethod(protocolLibrary, "getProtocolManager");
                    if (manager != null) {
                        ReflectionHelper.invokeMethod(manager, "removePacketListeners", targetPlugin);
                    }
                }
            } catch (Throwable t) {
                Log.debug("plugincleanup.protocollib-failed", t, "plugin", targetPlugin.getName());
            }
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PacketEvents") || Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            try {
                Class<?> peClass = ReflectionHelper.getClass("com.github.retrooper.packetevents.PacketEvents");
                if (peClass != null) {
                    Object api = ReflectionHelper.invokeStaticMethod(peClass, "getAPI");
                    if (api != null) {
                        Object eventManager = ReflectionHelper.invokeMethod(api, "getEventManager");
                        if (eventManager != null) {
                            ReflectionHelper.invokeMethod(eventManager, "unregisterListeners", targetPlugin);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.debug("plugincleanup.packetevents-failed", t, "plugin", targetPlugin.getName());
            }
        }
    }

    private void cleanThreads(Plugin targetPlugin, @Nullable ClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
            while (rootGroup.getParent() != null) {
                rootGroup = rootGroup.getParent();
            }

            Thread[] threads = new Thread[Math.max(rootGroup.activeCount() * 2, 64)];
            int count = rootGroup.enumerate(threads, true);

            for (int i = 0; i < count; i++) {
                Thread thread = threads[i];
                if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) continue;

                boolean matches = thread.getContextClassLoader() == classLoader
                        || thread.getClass().getClassLoader() == classLoader
                        || isThreadTargetLoadedBy(thread, classLoader);

                if (matches) {
                    try {
                        thread.setContextClassLoader(null);
                    } catch (Throwable ignored) {}
                    try {
                        thread.interrupt();
                    } catch (Throwable t) {
                        Log.debug("plugincleanup.thread-interrupt-failed", t, "thread", thread.getName(), "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.threads-failed", t, "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
        }
    }

    private static boolean isThreadTargetLoadedBy(Thread thread, ClassLoader classLoader) {
        try {
            Field targetField = ReflectionHelper.getField(Thread.class, "target");
            if (targetField != null) {
                Object target = targetField.get(thread);
                return target != null && target.getClass().getClassLoader() == classLoader;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void cleanJdbcDrivers(Plugin targetPlugin, @Nullable ClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            Enumeration<Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                Driver driver = drivers.nextElement();
                if (driver.getClass().getClassLoader() == classLoader) {
                    DriverManager.deregisterDriver(driver);
                }
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.jdbc-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void cleanPaperLifecycle(Plugin targetPlugin) {
        try {
            Object lifecycleManager = ReflectionHelper.invokeMethod(targetPlugin, "getLifecycleManager");
            if (lifecycleManager != null) {
                ReflectionHelper.invokeMethod(lifecycleManager, "unregisterAll");
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.paper-lifecycle-failed", t, "plugin", targetPlugin.getName());
        }
    }

    @SuppressWarnings("unchecked")
    private void cleanPaperDependencies(Plugin targetPlugin, @Nullable ClassLoader classLoader) {
        if (classLoader == null || Bukkit.getServer() == null) return;
        try {
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                ClassLoader otherCl = p.getClass().getClassLoader();
                if (otherCl != null && otherCl != classLoader) {
                    Collection<?> dependencies = ReflectionHelper.getFieldValue(otherCl, "dependencies");
                    if (dependencies != null) {
                        dependencies.remove(classLoader);
                    }
                    Collection<?> transitive = ReflectionHelper.getFieldValue(otherCl, "transitiveDependencies");
                    if (transitive != null) {
                        transitive.remove(classLoader);
                    }
                }
            }
            Collection<?> myDependencies = ReflectionHelper.getFieldValue(classLoader, "dependencies");
            if (myDependencies != null) {
                myDependencies.clear();
            }
            Collection<?> myTransitive = ReflectionHelper.getFieldValue(classLoader, "transitiveDependencies");
            if (myTransitive != null) {
                myTransitive.clear();
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.paper-dependencies-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void cleanStaticFields(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return;
        try {
            ClassLoader owner = targetPlugin.getClass().getClassLoader();
            Class<?> clazz = targetPlugin.getClass();
            while (clazz != null && clazz != Object.class && clazz.getClassLoader() == owner) {
                clearClassStaticFields(clazz);
                clazz = clazz.getSuperclass();
            }

            if (owner != null) {
                Map<?, ?> classes = ReflectionHelper.getFieldValue(owner, "classes");
                if (classes != null) {
                    for (Object classObj : new ArrayList<>(classes.values())) {
                        if (classObj instanceof Class<?> loadedClass && loadedClass.getClassLoader() == owner) {
                            clearClassStaticFields(loadedClass);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.static-fields-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void clearClassStaticFields(@Nullable Class<?> clazz) {
        if (clazz == null) return;
        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) {
                try {
                    f.setAccessible(true);
                    if (!Modifier.isFinal(f.getModifiers())) {
                        f.set(null, null);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void closeClassLoader(Plugin targetPlugin, @Nullable ClassLoader classLoader) {
        if (classLoader == null) return;

        closeInternalLoaders(targetPlugin);
        clearSafeClassDefinerLocks(targetPlugin, classLoader);
        detachFromPluginLoader(targetPlugin, classLoader);
        detachFromLoaderGroup(classLoader);
        clearClassTable(targetPlugin, classLoader);
        closeLoaderItself(targetPlugin, classLoader);
        closeUrlClassPath(targetPlugin, classLoader);
        closeJarHandle(targetPlugin, classLoader);
        clearBackReferences(targetPlugin, classLoader);

        MetaspaceCleanup.requestDeferred(plugin);
    }

    private void clearSafeClassDefinerLocks(Plugin targetPlugin, ClassLoader classLoader) {
        try {
            Class<?> safeClassDefiner = ReflectionHelper.getClass("com.destroystokyo.paper.util.SafeClassDefiner");
            if (safeClassDefiner == null) return;

            Map<?, ?> locks = ReflectionHelper.getStaticFieldValue(safeClassDefiner, "LOCKS");
            if (locks != null) {
                locks.remove(classLoader);
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.safe-class-definer-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void detachFromPluginLoader(Plugin targetPlugin, ClassLoader classLoader) {
        try {
            Object pluginLoader = ReflectionHelper.getFieldValue(targetPlugin, "loader");
            if (pluginLoader == null) {
                pluginLoader = ReflectionHelper.getFieldValue(classLoader, "loader");
            }
            if (pluginLoader == null) return;

            List<?> loaders = ReflectionHelper.getFieldValue(pluginLoader, "loaders");
            if (loaders != null) {
                loaders.remove(classLoader);
                for (Object otherLoader : loaders) {
                    if (otherLoader != null && otherLoader != classLoader) {
                        forgetClassesOf(ReflectionHelper.getFieldValue(otherLoader, "classes"), classLoader);
                    }
                }
            }
            forgetClassesOf(ReflectionHelper.getFieldValue(pluginLoader, "classes"), classLoader);
        } catch (Throwable t) {
            Log.debug("plugincleanup.javapluginloader-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private static void forgetClassesOf(@Nullable Map<?, ?> classes, ClassLoader classLoader) {
        if (classes == null) return;
        classes.values().removeIf(value -> value instanceof Class<?> clazz && clazz.getClassLoader() == classLoader);
    }

    private void detachFromLoaderGroup(ClassLoader classLoader) {
        try {
            Object group = ReflectionHelper.getFieldValue(classLoader, "group");
            if (group == null) return;

            Collection<?> groupLoaders = ReflectionHelper.getFieldValue(group, "loaders");
            if (groupLoaders != null) {
                groupLoaders.remove(classLoader);
            }
            Map<?, ?> groupMap = ReflectionHelper.getFieldValue(group, "map");
            if (groupMap != null) {
                groupMap.values().removeIf(loader -> loader == classLoader);
            }
        } catch (Throwable ignored) {
        }
    }

    private void clearClassTable(Plugin targetPlugin, ClassLoader classLoader) {
        try {
            Map<?, ?> classes = ReflectionHelper.getFieldValue(classLoader, "classes");
            if (classes != null) {
                classes.clear();
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.classes-table-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void closeLoaderItself(Plugin targetPlugin, ClassLoader classLoader) {
        if (!(classLoader instanceof Closeable closeable)) return;
        try {
            closeable.close();
        } catch (Throwable t) {
            Log.debug("plugincleanup.closeable-cl-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void closeUrlClassPath(Plugin targetPlugin, ClassLoader classLoader) {
        if (!(classLoader instanceof URLClassLoader urlClassLoader)) return;
        try {
            Object classPath = ReflectionHelper.getFieldValue(urlClassLoader, "ucp");
            if (classPath == null) return;

            List<?> loaders = ReflectionHelper.getFieldValue(classPath, "loaders");
            if (loaders == null) return;

            for (Object loader : loaders) {
                if (loader instanceof Closeable closeable) {
                    closeQuietly(closeable);
                }
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.urlclasspath-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void closeJarHandle(Plugin targetPlugin, ClassLoader classLoader) {
        try {
            Field jarField = firstField(classLoader, "jar", "jarFile", "file");
            if (jarField == null) return;

            if (jarField.get(classLoader) instanceof Closeable openJar) {
                closeQuietly(openJar);
            }
            trySetNull(jarField, classLoader);
        } catch (Throwable t) {
            Log.debug("plugincleanup.jar-close-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private void clearBackReferences(Plugin targetPlugin, ClassLoader classLoader) {
        try {
            Field pluginField = ReflectionHelper.getField(classLoader.getClass(), "plugin");
            if (pluginField != null) {
                pluginField.set(classLoader, null);
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.plugin-ref-failed", t, "plugin", targetPlugin.getName());
        }

        try {
            Field pluginInitField = ReflectionHelper.getField(classLoader.getClass(), "pluginInit");
            if (pluginInitField != null) {
                pluginInitField.set(classLoader, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static @Nullable Field firstField(Object owner, String... names) {
        for (String name : names) {
            Field field = ReflectionHelper.getField(owner.getClass(), name);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception | LinkageError ignored) {
        }
    }

    private static void trySetNull(Field field, Object owner) {
        try {
            field.set(owner, null);
        } catch (Exception | LinkageError ignored) {
        }
    }

    public void closeInternalLoaders(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) return;
        try {
            Class<?> clazz = targetPlugin.getClass();
            while (clazz != null && clazz != JavaPlugin.class && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(targetPlugin);
                        if (val instanceof ClassLoader loader && val instanceof AutoCloseable closeable) {
                            if (isSafeToCloseInternalLoader(loader, targetPlugin)) {
                                closeQuietly(closeable);
                            }
                        }
                    } catch (Exception | LinkageError t) {
                        Log.debug("plugincleanup.field-close-failed", t, "field", f.getName(), "plugin", targetPlugin.getName());
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception | LinkageError t) {
            Log.debug("plugincleanup.internal-loaders-failed", t, "plugin", targetPlugin.getName());
        }
    }

    private static boolean isSafeToCloseInternalLoader(ClassLoader loader, Plugin targetPlugin) {
        if (loader == targetPlugin.getClass().getClassLoader()) {
            return false;
        }
        ClassLoader pluginFieldCl = null;
        try {
            Field f = ReflectionHelper.getField(targetPlugin.getClass(), "classLoader");
            if (f != null) {
                f.setAccessible(true);
                Object val = f.get(targetPlugin);
                if (val instanceof ClassLoader cl) {
                    pluginFieldCl = cl;
                    if (loader == cl) {
                        return false;
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
        if (loader == ClassLoader.getSystemClassLoader()) {
            return false;
        }
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        if (platform != null && loader == platform) {
            return false;
        }
        if (Bukkit.class.getClassLoader() != null && loader == Bukkit.class.getClassLoader()) {
            return false;
        }
        if (SanitizerManager.class.getClassLoader() != null && loader == SanitizerManager.class.getClassLoader()) {
            return false;
        }
        for (ClassLoader parent = targetPlugin.getClass().getClassLoader(); parent != null; parent = parent.getParent()) {
            if (loader == parent) {
                return false;
            }
        }
        if (pluginFieldCl != null) {
            for (ClassLoader parent = pluginFieldCl; parent != null; parent = parent.getParent()) {
                if (loader == parent) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean canProceed(@Nullable Plugin targetPlugin) {
        return targetPlugin != null && Bukkit.getServer() != null;
    }

    public static void cleanupServerState(@Nullable Plugin targetPlugin) {
        if (!canProceed(targetPlugin)) return;
        ClassLoader targetCl = targetPlugin.getClass().getClassLoader();

        closePluginInventories(targetPlugin, targetCl);
        cleanPermissionAttachments(targetPlugin);
        cleanPermissions(targetPlugin);
        cleanMetadata(targetPlugin);
        cleanRecipes(targetPlugin);
        cleanBossBars(targetPlugin);
        cleanHelpTopics(targetPlugin);
    }

    public static void cleanupAll(@Nullable Plugin targetPlugin) {
        cleanupServerState(targetPlugin);
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
                    InventoryView view = player.getOpenInventory();
                    if (view == null) return;
                    Inventory top = view.getTopInventory();
                    if (top == null) return;
                    InventoryHolder holder = top.getHolder();
                    boolean isPluginInventory = (holder != null && holder.getClass().getClassLoader() == targetCl)
                            || top.getClass().getClassLoader() == targetCl;
                    if (isPluginInventory) {
                        player.closeInventory();
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    Log.debug("serverstatecleanup.inventory-close-error", t, "player", player.getName());
                }
            });
        }
        if (failed.get() > 0) {
            Log.debug("serverstatecleanup.inventory-close-failed-count", "count", String.valueOf(failed.get()), "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
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
                    Set<PermissionAttachment> toRemove = new HashSet<>();
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
            Log.debug("serverstatecleanup.attachment-cleanup-failed-count", "count", String.valueOf(failed.get()), "plugin", targetPlugin != null ? targetPlugin.getName() : "null");
        }
    }

    public static Set<String> permissionPrefixesFor(String pluginName, List<String> provides) {
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

    private static List<String> getProvides(Plugin targetPlugin) {
        if (targetPlugin == null) return Collections.emptyList();
        try {
            Object meta = ReflectionHelper.invokeMethod(targetPlugin, "getPluginMeta");
            if (meta != null) {
                List<String> provides = ReflectionHelper.invokeMethod(meta, "getProvides");
                if (provides != null && !provides.isEmpty()) {
                    return provides;
                }
            }
        } catch (Throwable ignored) {}
        try {
            PluginDescriptionFile desc = targetPlugin.getDescription();
            if (desc != null && desc.getProvides() != null) {
                return desc.getProvides();
            }
        } catch (Throwable ignored) {}
        return Collections.emptyList();
    }

    private static void cleanPermissions(Plugin targetPlugin) {
        try {
            Set<String> prefixes = permissionPrefixesFor(
                    targetPlugin.getName(), getProvides(targetPlugin));

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
            List<NamespacedKey> fallbackKeys = new ArrayList<>();
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe recipe = it.next();
                if (recipe instanceof Keyed keyed) {
                    if (keyed.getKey().getNamespace().equalsIgnoreCase(namespace)) {
                        fallbackKeys.add(keyed.getKey());
                    }
                }
            }
            for (NamespacedKey key : fallbackKeys) {
                Bukkit.removeRecipe(key);
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
                        if (topic.getClass().getName().contains("Command")) {
                            Plugin p = ReflectionHelper.getFieldValue(topic, "plugin");
                            return targetPlugin.equals(p);
                        }
                        return false;
                    });
                }
            }
        } catch (Throwable t) {
            Log.debug("serverstatecleanup.helptopics-cleanup-failed", t, "plugin", targetPlugin.getName());
        }
    }
}