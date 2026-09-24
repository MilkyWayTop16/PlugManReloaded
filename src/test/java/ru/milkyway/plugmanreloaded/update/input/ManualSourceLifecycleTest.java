package ru.milkyway.plugmanreloaded.update.input;

import ru.milkyway.plugmanreloaded.update.input.ManualSources.ManualSourceListener;
import ru.milkyway.plugmanreloaded.update.input.ManualSources.ManualSourceSession;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.BukkitServerMock;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ManualSourceLifecycleTest {

    private static sun.misc.Unsafe unsafe;

    @BeforeAll
    static void init() throws Exception {
        BukkitServerMock.ensureInitialized();
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);
    }

    @AfterEach
    void tearDown() {
        BukkitServerMock.resetServer();
    }

    private Player createMockPlayer(UUID uuid, String name, boolean online) {
        return (Player) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> name;
                    case "isOnline" -> online;
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> args != null && args.length == 1 && args[0] == proxy;
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0)
                            : null;
                }
        );
    }

    private PlugManReloaded createMockPlugin() throws Exception {
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        ConfigManager cm = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);

        Field cmField = PlugManReloaded.class.getDeclaredField("configManager");
        cmField.setAccessible(true);
        cmField.set(plugin, cm);

        return plugin;
    }

    @Test
    @DisplayName("Verify ManualSources does not implement Listener and ManualSourceListener handles PlayerQuitEvent")
    void testListenerSeparation() throws Exception {
        assertFalse(Listener.class.isAssignableFrom(ManualSources.class));
        assertTrue(Listener.class.isAssignableFrom(ManualSourceListener.class));

        Method quitMethod = ManualSourceListener.class.getDeclaredMethod("onQuit", PlayerQuitEvent.class);
        assertNotNull(quitMethod);
        assertTrue(quitMethod.isAnnotationPresent(EventHandler.class));
    }

    @Test
    @DisplayName("Verify session lifecycle on player quit evicts session and cancels timeout task")
    void testSessionLifecycleOnQuit() throws Exception {
        PlugManReloaded plugin = createMockPlugin();
        ManualSources manualSources = new ManualSources(plugin);
        ManualSourceListener listener = new ManualSourceListener(plugin, manualSources);

        UUID uuid = UUID.randomUUID();
        Player player = createMockPlayer(uuid, "Player1", true);

        AtomicBoolean taskCancelled = new AtomicBoolean(false);
        BukkitTask fakeTask = (BukkitTask) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        taskCancelled.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        ManualSourceSession session = new ManualSourceSession("TestPlugin", "com.example.Test", fakeTask);

        Field sessionsField = ManualSources.class.getDeclaredField("activeSessions");
        sessionsField.setAccessible(true);
        ConcurrentHashMap<UUID, ManualSourceSession> activeSessions =
                (ConcurrentHashMap<UUID, ManualSourceSession>) sessionsField.get(manualSources);
        activeSessions.put(uuid, session);

        assertNotNull(manualSources.get(player));
        assertNotNull(manualSources.get(uuid));

        PlayerQuitEvent quitEvent = new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null);
        listener.onQuit(quitEvent);

        assertNull(manualSources.get(player));
        assertNull(manualSources.get(uuid));
        assertTrue(taskCancelled.get());
    }

    @Test
    @DisplayName("Verify re-joining player after quit starts with clean session state")
    void testRejoiningPlayerState() throws Exception {
        PlugManReloaded plugin = createMockPlugin();
        ManualSources manualSources = new ManualSources(plugin);
        ManualSourceListener listener = new ManualSourceListener(plugin, manualSources);

        UUID uuid = UUID.randomUUID();
        Player playerFirstJoin = createMockPlayer(uuid, "Steve", true);

        AtomicBoolean task1Cancelled = new AtomicBoolean(false);
        BukkitTask task1 = (BukkitTask) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        task1Cancelled.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        ManualSourceSession session1 = new ManualSourceSession("PluginA", "com.example.A", task1);
        Field sessionsField = ManualSources.class.getDeclaredField("activeSessions");
        sessionsField.setAccessible(true);
        ConcurrentHashMap<UUID, ManualSourceSession> activeSessions =
                (ConcurrentHashMap<UUID, ManualSourceSession>) sessionsField.get(manualSources);
        activeSessions.put(uuid, session1);

        PlayerQuitEvent quitEvent = new PlayerQuitEvent(playerFirstJoin, (net.kyori.adventure.text.Component) null);
        listener.onQuit(quitEvent);
        assertTrue(task1Cancelled.get());
        assertNull(manualSources.get(playerFirstJoin));

        Player playerSecondJoin = createMockPlayer(uuid, "Steve", true);
        assertNull(manualSources.get(playerSecondJoin));

        AtomicBoolean task2Cancelled = new AtomicBoolean(false);
        BukkitTask task2 = (BukkitTask) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        task2Cancelled.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        ManualSourceSession session2 = new ManualSourceSession("PluginB", "com.example.B", task2);
        activeSessions.put(uuid, session2);

        ManualSourceSession retrieved = manualSources.get(playerSecondJoin);
        assertNotNull(retrieved);
        assertEquals("PluginB", retrieved.getPluginName());
    }

    @Test
    @DisplayName("Verify cancel and timeout cancel pending tasks and clean active sessions")
    void testCancelAndTimeout() throws Exception {
        PlugManReloaded plugin = createMockPlugin();
        ManualSources manualSources = new ManualSources(plugin);

        UUID uuid1 = UUID.randomUUID();
        AtomicBoolean task1Cancelled = new AtomicBoolean(false);
        BukkitTask task1 = (BukkitTask) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        task1Cancelled.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        ManualSourceSession session1 = new ManualSourceSession("Vault", "net.milkbowl.vault.Vault", task1);

        Field sessionsField = ManualSources.class.getDeclaredField("activeSessions");
        sessionsField.setAccessible(true);
        ConcurrentHashMap<UUID, ManualSourceSession> activeSessions =
                (ConcurrentHashMap<UUID, ManualSourceSession>) sessionsField.get(manualSources);
        activeSessions.put(uuid1, session1);

        assertTrue(manualSources.cancel(uuid1));
        assertTrue(task1Cancelled.get());
        assertNull(manualSources.get(uuid1));
        assertFalse(manualSources.cancel(uuid1));

        UUID uuid2 = UUID.randomUUID();
        AtomicBoolean task2Cancelled = new AtomicBoolean(false);
        BukkitTask task2 = (BukkitTask) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        task2Cancelled.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        ManualSourceSession session2 = new ManualSourceSession("LuckPerms", "net.luckperms.api.LuckPerms", task2);
        activeSessions.put(uuid2, session2);

        Method handleTimeout = ManualSources.class.getDeclaredMethod("handleTimeout", UUID.class);
        handleTimeout.setAccessible(true);
        handleTimeout.invoke(manualSources, uuid2);

        assertTrue(task2Cancelled.get());
        assertNull(manualSources.get(uuid2));
    }

    @Test
    @DisplayName("Verify shutdown cancels all sessions and empties map")
    void testShutdown() throws Exception {
        PlugManReloaded plugin = createMockPlugin();
        ManualSources manualSources = new ManualSources(plugin);

        Field sessionsField = ManualSources.class.getDeclaredField("activeSessions");
        sessionsField.setAccessible(true);
        ConcurrentHashMap<UUID, ManualSourceSession> activeSessions =
                (ConcurrentHashMap<UUID, ManualSourceSession>) sessionsField.get(manualSources);

        AtomicBoolean t1Cancelled = new AtomicBoolean(false);
        BukkitTask t1 = (BukkitTask) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        t1Cancelled.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        AtomicBoolean t2Cancelled = new AtomicBoolean(false);
        BukkitTask t2 = (BukkitTask) Proxy.newProxyInstance(
                ManualSourceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        t2Cancelled.set(true);
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                }
        );

        activeSessions.put(UUID.randomUUID(), new ManualSourceSession("P1", "c.e.P1", t1));
        activeSessions.put(UUID.randomUUID(), new ManualSourceSession("P2", "c.e.P2", t2));

        assertEquals(2, activeSessions.size());

        manualSources.shutdown();

        assertTrue(t1Cancelled.get());
        assertTrue(t2Cancelled.get());
        assertEquals(0, activeSessions.size());
    }

    @Test
    @DisplayName("Verify ManualSourceSession has no Player references")
    void testSessionNoPlayerField() {
        for (Field f : ManualSourceSession.class.getDeclaredFields()) {
            assertFalse(Player.class.isAssignableFrom(f.getType()));
        }
    }
}
