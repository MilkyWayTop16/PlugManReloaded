package ru.milkyway.plugmanreloaded.utils;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogWithoutServerTest {

    @Test
    void logNeverThrowsWhenServerIsNotUp() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object server = serverField.get(null);
        Field instanceField = PlugManReloaded.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object instance = instanceField.get(null);

        try {
            serverField.set(null, null);
            instanceField.set(null, null);

            String why = "Логирование должно работать без Bukkit-сервера";

            assertDoesNotThrow(() -> Log.info("проверка"), why);
            assertDoesNotThrow(() -> Log.success("проверка"), why);
            assertDoesNotThrow(() -> Log.warn("проверка"), why);
            assertDoesNotThrow(() -> Log.warn("проверка", new IllegalStateException("причина")), why);
            assertDoesNotThrow(() -> Log.error("проверка"), why);
            assertDoesNotThrow(() -> Log.error("проверка", new IllegalStateException("причина")), why);
            assertDoesNotThrow(() -> Log.console("проверка"), why);
            assertDoesNotThrow(() -> Log.debug("проверка"), why);
            assertDoesNotThrow(() -> Log.debug("проверка", new IllegalStateException("причина")), why);
            assertDoesNotThrow(() -> Log.debugPlain("проверка"), why);
            assertDoesNotThrow(() -> Log.info(null), why);
        } finally {
            instanceField.set(null, instance);
            serverField.set(null, server);
        }
    }
}
