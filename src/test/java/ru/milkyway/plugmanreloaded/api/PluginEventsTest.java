package ru.milkyway.plugmanreloaded.api;

import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.api.event.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class PluginEventsTest {

    @Test
    void testPreLoadEvent() {
        File file = new File("Test.jar");
        PluginPreLoadEvent event = new PluginPreLoadEvent("Test", file);

        assertEquals("Test", event.getPluginName());
        assertEquals(file, event.getJarFile());
        assertFalse(event.isCancelled());

        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertNotNull(event.getHandlers());
        assertNotNull(PluginPreLoadEvent.getHandlerList());
    }

    @Test
    void testLoadedEvent() {
        File file = new File("Test.jar");
        PluginLoadedEvent event = new PluginLoadedEvent(null, file);

        assertEquals("Test.jar", event.getPluginName());
        assertEquals(file, event.getJarFile());
        assertNull(event.getPlugin());
        assertNotNull(event.getHandlers());
    }

    @Test
    void testPreUnloadEvent() {
        PluginPreUnloadEvent event = new PluginPreUnloadEvent(null, true);

        assertEquals("Unknown", event.getPluginName());
        assertTrue(event.isDeep());
        assertFalse(event.isCancelled());

        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertNotNull(event.getHandlers());
    }

    @Test
    void testUnloadedEvent() {
        PluginUnloadedEvent event = new PluginUnloadedEvent("LuckPerms", true);

        assertEquals("LuckPerms", event.getPluginName());
        assertTrue(event.isDeep());
        assertNotNull(event.getHandlers());
    }

    @Test
    void testPreReloadEvent() {
        PluginPreReloadEvent event = new PluginPreReloadEvent(null);

        assertEquals("Unknown", event.getPluginName());
        assertFalse(event.isCancelled());

        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertNotNull(event.getHandlers());
    }

    @Test
    void testReloadedEvent() {
        PluginReloadedEvent event = new PluginReloadedEvent(null, 150L);

        assertEquals("Unknown", event.getPluginName());
        assertEquals(150L, event.getElapsedMs());
        assertNotNull(event.getHandlers());
    }

    @Test
    void testUpdateFoundEvent() {
        UpdateInfo info = new UpdateInfo(
                "Vault",
                "1.7.2",
                "1.7.3",
                "spigot",
                "https://spigotmc.org",
                false,
                false,
                true
        );
        PluginUpdateFoundEvent event = new PluginUpdateFoundEvent(null, info);

        assertEquals("Vault", event.getPluginName());
        assertEquals(info, event.getUpdateInfo());
        assertNotNull(event.getHandlers());
    }
}
