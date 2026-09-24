package ru.milkyway.plugmanreloaded.managers;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.managers.ActionManager;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ServerStateCleanupFoliaTest {

    private Unsafe unsafe;
    private Object foliaBase;
    private long foliaOffset;
    private boolean originalFolia;
    private Object originalServer;

    @BeforeEach
    void setUp() throws Exception {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = (Unsafe) theUnsafe.get(null);

        Field foliaField = PlatformDetector.class.getDeclaredField("FOLIA");
        foliaOffset = unsafe.staticFieldOffset(foliaField);
        foliaBase = unsafe.staticFieldBase(foliaField);
        originalFolia = unsafe.getBoolean(foliaBase, foliaOffset);

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        originalServer = serverField.get(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        unsafe.putBoolean(foliaBase, foliaOffset, originalFolia);
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, originalServer);
    }

    @Test
    @DisplayName("Verify runForEntity executes immediately on Paper primary thread")
    void testRunForEntityPaperPrimaryThread() {
        AtomicBoolean ran = new AtomicBoolean(false);
        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null
        );

        TaskScheduler.runForEntity(null, player, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    @DisplayName("Verify runForEntity executes immediately on Folia when region is owned")
    void testRunForEntityFoliaOwnedRegion() throws Exception {
        unsafe.putBoolean(foliaBase, foliaOffset, true);

        AtomicBoolean ran = new AtomicBoolean(false);
        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("isOnline".equals(method.getName())) return true;
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("isOwnedByCurrentRegion".equals(method.getName())) {
                        return true;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        TaskScheduler.runForEntity(null, player, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    @DisplayName("Verify runForEntity delegates to EntityScheduler on Folia when region is not owned")
    void testRunForEntityFoliaUnownedRegion() throws Exception {
        unsafe.putBoolean(foliaBase, foliaOffset, true);

        AtomicBoolean executedOnEntityScheduler = new AtomicBoolean(false);

        EntityScheduler mockEntityScheduler = (EntityScheduler) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{EntityScheduler.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        executedOnEntityScheduler.set(true);
                        Runnable r = (Runnable) args[1];
                        r.run();
                        return true;
                    }
                    return null;
                }
        );

        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getScheduler".equals(method.getName())) {
                        return mockEntityScheduler;
                    }
                    if ("isOnline".equals(method.getName())) return true;
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("isOwnedByCurrentRegion".equals(method.getName())) {
                        return false;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        Plugin mockPlugin = (Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null
        );

        AtomicBoolean taskRan = new AtomicBoolean(false);
        TaskScheduler.runForEntity(mockPlugin, player, () -> taskRan.set(true));

        assertTrue(executedOnEntityScheduler.get());
        assertTrue(taskRan.get());
    }

    @Test
    @DisplayName("Verify closeAllOnlineInventories closes open inventory for online players")
    void testCloseAllOnlineInventories() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);

        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("isOnline".equals(method.getName())) return true;
                    if ("closeInventory".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("getOnlinePlayers".equals(method.getName())) {
                        return List.of(player);
                    }
                    if ("isPrimaryThread".equals(method.getName())) {
                        return true;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        SanitizerManager.closeAllOnlineInventories();
        assertTrue(closed.get());
    }

    @Test
    @DisplayName("Verify closeAllOnlineInventories skips disconnected players")
    void testCloseAllOnlineInventoriesSkipsDisconnected() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);

        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("isOnline".equals(method.getName())) return false;
                    if ("closeInventory".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("getOnlinePlayers".equals(method.getName())) {
                        return List.of(player);
                    }
                    if ("isPrimaryThread".equals(method.getName())) {
                        return true;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        SanitizerManager.closeAllOnlineInventories();
        assertFalse(closed.get());
    }

    @Test
    @DisplayName("Verify cleanupAll handles online and disconnected player permissions gracefully")
    void testCleanupAllPlayerSafety() throws Exception {
        PluginDescriptionFile desc = new PluginDescriptionFile("TestPlugin", "1.0", "some.main.Class");
        ClassLoader pluginCl = new URLClassLoader(new URL[0], getClass().getClassLoader());
        Plugin targetPlugin = (Plugin) Proxy.newProxyInstance(
                pluginCl,
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "TestPlugin";
                    if ("getDescription".equals(method.getName())) return desc;
                    if ("isEnabled".equals(method.getName())) return true;
                    if ("getClassLoader".equals(method.getName())) return pluginCl;
                    if ("equals".equals(method.getName())) return args[0] == proxy;
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        AtomicBoolean removed = new AtomicBoolean(false);

        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("isOnline".equals(method.getName())) return true;
                    if ("getEffectivePermissions".equals(method.getName())) {
                        PermissionAttachment attachment = new PermissionAttachment(targetPlugin, (Player) proxy);
                        PermissionAttachmentInfo info = new PermissionAttachmentInfo((Player) proxy, "testplugin.admin", attachment, true);
                        return Set.of(info);
                    }
                    if ("removeAttachment".equals(method.getName())) {
                        removed.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("getOnlinePlayers".equals(method.getName())) return List.of(player);
                    if ("isPrimaryThread".equals(method.getName())) return true;
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        SanitizerManager.cleanupServerState(targetPlugin);
        assertTrue(removed.get());
    }

    @Test
    @DisplayName("Verify ActionManager player-command executes on entity thread and respects disconnect")
    void testActionManagerPlayerCommandThreadSafety() throws Exception {
        AtomicBoolean performed = new AtomicBoolean(false);
        AtomicBoolean isOnline = new AtomicBoolean(true);

        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("isOnline".equals(method.getName())) return isOnline.get();
                    if ("performCommand".equals(method.getName())) {
                        performed.set(true);
                        return true;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        ActionManager actionManager = new ActionManager(null, null);
        actionManager.executeRawAction(player, "[player-command] testcmd", Collections.emptyMap());
        assertTrue(performed.get());

        performed.set(false);
        isOnline.set(false);
        actionManager.executeRawAction(player, "[player-command] testcmd", Collections.emptyMap());
        assertFalse(performed.get());
    }

    @Test
    @DisplayName("Verify ActionManager console-command routes to GlobalRegionScheduler on Folia")
    void testActionManagerConsoleCommandFoliaRouting() throws Exception {
        unsafe.putBoolean(foliaBase, foliaOffset, true);

        AtomicBoolean ranOnGlobalRegion = new AtomicBoolean(false);
        GlobalRegionScheduler mockGlobalScheduler = (GlobalRegionScheduler) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{GlobalRegionScheduler.class},
                (proxy, method, args) -> {
                    if ("run".equals(method.getName())) {
                        ranOnGlobalRegion.set(true);
                    }
                    return null;
                }
        );

        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("getGlobalRegionScheduler".equals(method.getName())) {
                        return mockGlobalScheduler;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        Field foliaGlobalField = TaskScheduler.class.getDeclaredField("foliaGlobalRegionScheduler");
        foliaGlobalField.setAccessible(true);
        foliaGlobalField.set(null, null);

        PlugManReloaded mockPlugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        ActionManager actionManager = new ActionManager(mockPlugin, null);

        actionManager.executeRawAction(Bukkit.getConsoleSender(), "[console-command] reload", Collections.emptyMap());
        assertTrue(ranOnGlobalRegion.get());
    }
}
