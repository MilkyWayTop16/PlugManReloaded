package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LogConsoleFilteringTest {

    @AfterEach
    void tearDown() throws Exception {
        Log.invalidateEarlyCache();
        Field instanceField = PlugManReloaded.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private PlugManReloaded createPluginInstance(File dataFolder) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        Class<?> cur = plugin.getClass();
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField("dataFolder");
                f.setAccessible(true);
                f.set(plugin, dataFolder);
                break;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return plugin;
    }

    @Test
    @DisplayName("Verify earlyConsoleLogsEnabled reads settings.logs-in-console.enable false by default")
    void testEarlyConsoleLogsDisabledByDefault(@TempDir Path tempDir) throws Exception {
        File dataFolder = tempDir.toFile();
        File configFile = new File(dataFolder, "config.yml");
        Files.writeString(configFile.toPath(), """
                settings:
                  logs-in-console:
                    enable: false
                    debug: false
                """);

        PlugManReloaded mockInstance = createPluginInstance(dataFolder);

        Field instanceField = PlugManReloaded.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, mockInstance);

        Log.invalidateEarlyCache();
        assertFalse(Log.isConsoleLogsEnabled());
        assertFalse(Log.isDebugEnabled());
    }

    @Test
    @DisplayName("Verify earlyConsoleLogsEnabled reads settings.logs-in-console.enable true when configured")
    void testEarlyConsoleLogsEnabledWhenConfigured(@TempDir Path tempDir) throws Exception {
        File dataFolder = tempDir.toFile();
        File configFile = new File(dataFolder, "config.yml");
        Files.writeString(configFile.toPath(), """
                settings:
                  logs-in-console:
                    enable: true
                    debug: true
                """);

        PlugManReloaded mockInstance = createPluginInstance(dataFolder);

        Field instanceField = PlugManReloaded.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, mockInstance);

        Log.invalidateEarlyCache();
        assertTrue(Log.isConsoleLogsEnabled());
        assertTrue(Log.isDebugEnabled());
    }

    @Test
    @DisplayName("Verify isConsoleLogsEnabled returns false when plugin is null")
    void testWhenPluginIsNull() throws Exception {
        Field instanceField = PlugManReloaded.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        Log.invalidateEarlyCache();
        assertFalse(Log.isConsoleLogsEnabled());
        assertFalse(Log.isDebugEnabled());
    }
}