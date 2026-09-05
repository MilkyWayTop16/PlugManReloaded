package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.MetaspaceCleanup;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;

import java.beans.Introspector;
import java.io.Closeable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.*;

public class PluginCleanup {

    private final PlugManReloaded plugin;

    public PluginCleanup(PlugManReloaded plugin) {
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

        ServerStateCleanup.cleanupAll(targetPlugin);

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
                collection.removeIf(item -> {
                    Plugin p = ReflectionHelper.getFieldValue(item, "plugin");
                    return targetPlugin.equals(p);
                });
            } else if (val instanceof Map<?, ?> mapVal) {
                for (Object subVal : mapVal.values()) {
                    if (subVal instanceof Collection<?> subCollection) {
                        subCollection.removeIf(item -> {
                            Plugin p = ReflectionHelper.getFieldValue(item, "plugin");
                            return targetPlugin.equals(p);
                        });
                    }
                }
            }
        }
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

                if (thread.getContextClassLoader() == classLoader || thread.getClass().getClassLoader() == classLoader) {
                    try {
                        thread.setContextClassLoader(null);
                    } catch (Throwable ignored) {}
                    try {
                        thread.interrupt();
                    } catch (Throwable t) {
                        Log.debug("plugincleanup.thread-interrupt-failed", t, "thread", thread.getName(), "plugin", targetPlugin.getName());
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("plugincleanup.threads-failed", t, "plugin", targetPlugin.getName());
        }
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
        if (PluginCleanup.class.getClassLoader() != null && loader == PluginCleanup.class.getClassLoader()) {
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
}

