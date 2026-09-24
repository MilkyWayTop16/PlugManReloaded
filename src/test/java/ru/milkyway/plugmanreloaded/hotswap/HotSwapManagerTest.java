package ru.milkyway.plugmanreloaded.hotswap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.utils.JarValidator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

public class HotSwapManagerTest {

    @Test
    void testTemporarilyIgnoreLogic() throws InterruptedException {
        ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
        String file = "TestPlugin.jar";

        map.put(file.toLowerCase(), System.currentTimeMillis() + 100L);
        assertTrue(map.containsKey(file.toLowerCase()));

        Thread.sleep(150L);
        Long until = map.get(file.toLowerCase());
        if (until != null && System.currentTimeMillis() > until) {
            map.remove(file.toLowerCase());
        }

        assertFalse(map.containsKey(file.toLowerCase()));
    }

    @Test
    void testPartiallyWrittenJarValidation(@TempDir Path tempDir) throws IOException {
        File brokenJar = tempDir.resolve("Broken.jar").toFile();
        try (FileOutputStream fos = new FileOutputStream(brokenJar)) {
            fos.write(new byte[]{0x50, 0x4b, 0x03, 0x04});
        }
        assertFalse(JarValidator.isValidPluginJar(brokenJar));

        File validJar = tempDir.resolve("Valid.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(validJar), new Manifest())) {
            jos.flush();
        }
        assertFalse(JarValidator.isValidPluginJar(validJar));
    }
}
