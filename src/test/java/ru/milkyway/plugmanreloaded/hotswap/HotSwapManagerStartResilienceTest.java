package ru.milkyway.plugmanreloaded.hotswap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.BukkitServerMock;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.managers.HotSwapManager;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HotSwapManagerStartResilienceTest {

    @BeforeAll
    static void initServer() {
        BukkitServerMock.ensureInitialized();
    }

    @Test
    void startCatchesEveryThrowableNotJustIoException() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Unsafe unsafe = (Unsafe) f.get(null);

        File throwingPluginsDir = new File("dummy-plugins") {
            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public Path toPath() {
                throw new UnsupportedOperationException("Simulated non-IOException failure");
            }
        };

        File dummyDataFolder = new File(throwingPluginsDir, "PlugManReloaded");

        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        Field dfField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
        dfField.setAccessible(true);
        dfField.set(plugin, dummyDataFolder);

        LifecycleManager lifecycleManager = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        HotSwapManager manager = (HotSwapManager) unsafe.allocateInstance(HotSwapManager.class);

        Field pField = HotSwapManager.class.getDeclaredField("plugin");
        pField.setAccessible(true);
        pField.set(manager, plugin);

        Field lmField = HotSwapManager.class.getDeclaredField("lifecycleManager");
        lmField.setAccessible(true);
        lmField.set(manager, lifecycleManager);

        assertDoesNotThrow(manager::start);
        assertFalse(manager.isRunning());
    }
}
