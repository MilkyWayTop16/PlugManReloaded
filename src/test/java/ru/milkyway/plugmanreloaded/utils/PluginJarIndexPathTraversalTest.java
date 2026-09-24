package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginJarIndexPathTraversalTest {

    @TempDir
    Path root;

    @Test
    void filesOutsideThePluginsDirectoryAreRejected() throws Exception {
        Path plugins = Files.createDirectory(root.resolve("plugins"));
        Path outside = Files.writeString(root.resolve("server.properties"), "secret=1");
        Path inside = Files.writeString(plugins.resolve("Real.jar"), "x");

        File pluginsDir = plugins.toFile();

        assertTrue(PluginJarIndex.isInsidePluginsDir(pluginsDir, inside.toFile()),
                "обычный jar внутри plugins/ обязан проходить проверку, иначе сломается штатное удаление");

        assertFalse(PluginJarIndex.isInsidePluginsDir(pluginsDir, outside.toFile()),
                "файл в корне сервера не имеет права считаться плагином");

        assertFalse(PluginJarIndex.isInsidePluginsDir(pluginsDir, new File(pluginsDir, ".." + File.separator + "server.properties")),
                "путь с «..» обязан отклоняться ПОСЛЕ канонизации: именно так «/plm delete ../server.properties» "
                        + "удалял server.properties на живом сервере");

        assertFalse(PluginJarIndex.isInsidePluginsDir(pluginsDir,
                        new File(pluginsDir, ".." + File.separator + ".." + File.separator + "anything.jar")),
                "многоуровневый выход из папки тоже обязан отклоняться");
    }

    @Test
    void nullsAreRejectedRatherThanThrowing() {
        assertFalse(PluginJarIndex.isInsidePluginsDir(null, new File("x")));
        assertFalse(PluginJarIndex.isInsidePluginsDir(new File("x"), null));
    }

    @Test
    void everyDirectFileLookupInsideFindIsGuarded() throws Exception {
        Path plugins = Files.createDirectory(root.resolve("test_plugins"));
        Path outside = Files.writeString(root.resolve("sensitive.properties"), "token=123");
        Path inside = Files.writeString(plugins.resolve("Allowed.jar"), "dummy");

        sun.misc.Unsafe unsafe;
        java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        unsafe = (sun.misc.Unsafe) uf.get(null);
        ru.milkyway.plugmanreloaded.PlugManReloaded plugin =
                (ru.milkyway.plugmanreloaded.PlugManReloaded) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.PlugManReloaded.class);
        File dataFolder = new File(plugins.toFile(), "PlugManReloaded");
        java.lang.reflect.Field df = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
        df.setAccessible(true);
        df.set(plugin, dataFolder);

        PluginJarIndex index = new PluginJarIndex(plugin);

        org.junit.jupiter.api.Assertions.assertNull(index.find("../sensitive.properties"));
        org.junit.jupiter.api.Assertions.assertNull(index.find("..\\sensitive.properties"));
        org.junit.jupiter.api.Assertions.assertNull(index.find("../../sensitive.properties"));
        org.junit.jupiter.api.Assertions.assertNull(index.find(outside.toAbsolutePath().toString()));
        org.junit.jupiter.api.Assertions.assertNotNull(index.find("Allowed.jar"));
    }
}
