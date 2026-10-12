package ru.milkyway.plugmanreloaded.update.install;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.configs.MainConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchUpdaterSafetyTest {

    @Test
    void testBaselineUnsafePluginsAlwaysPresent() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("settings.unsafe-to-unload", List.of("CustomPlugin"));

        MainConfig config = new MainConfig(yaml);
        assertTrue(config.isUnsafeToUnload("CustomPlugin"));
        assertTrue(config.isUnsafeToUnload("WorldGuard"));
        assertTrue(config.isUnsafeToUnload("Essentials"));
        assertTrue(config.isUnsafeToUnload("MoneyFromMobs"));
        assertTrue(config.isUnsafeToUnload("SkinsRestorer"));
        assertTrue(config.isUnsafeToUnload("Vault"));
        assertTrue(config.isUnsafeToUnload("LuckPerms"));
        assertFalse(config.isUnsafeToUnload("SomeRandomSafePlugin"));
    }
}
