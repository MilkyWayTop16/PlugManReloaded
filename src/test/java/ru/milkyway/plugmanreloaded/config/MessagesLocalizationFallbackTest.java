package ru.milkyway.plugmanreloaded.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.BukkitServerMock;
import ru.milkyway.plugmanreloaded.managers.ActionManager;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MessagesLocalizationFallbackTest {

    @BeforeAll
    static void initServer() {
        BukkitServerMock.ensureInitialized();
    }

    @Test
    @DisplayName("Verify Russian defaults contain Russian strings")
    void testRuLanguageLoadsRussianDefaults() {
        ConfigManager manager = new ConfigManager(null);
        FileConfiguration defaults = manager.resolveMessageDefaults("ru");
        assertNotNull(defaults);
        assertEquals("не удалось выгрузить", defaults.getString("actions.errors.details.unload-failed"));
    }

    @Test
    @DisplayName("Verify English defaults contain English strings")
    void testEnLanguageLoadsEnglishDefaults() {
        ConfigManager manager = new ConfigManager(null);
        FileConfiguration defaults = manager.resolveMessageDefaults("en");
        assertNotNull(defaults);
        assertEquals("failed to unload", defaults.getString("actions.errors.details.unload-failed"));
    }

    @Test
    @DisplayName("Verify missing key in user config falls back to active language default")
    void testMissingKeyInConfigFallsBackToLanguageDefault() {
        ConfigManager manager = new ConfigManager(null);
        FileConfiguration enDefaults = manager.resolveMessageDefaults("en");

        YamlConfiguration userConfig = new YamlConfiguration();
        userConfig.set("actions.errors.details.load-failed", "custom load failed");
        userConfig.setDefaults(enDefaults);

        assertEquals("custom load failed", userConfig.getString("actions.errors.details.load-failed"));
        assertEquals("failed to unload", userConfig.getString("actions.errors.details.unload-failed"));
        assertNull(userConfig.getString("totally.unknown.key"));
    }

    @Test
    @DisplayName("Verify custom language falls back to English when key is missing")
    void testCustomLanguageMissingKeyFallsBackToEnglish() {
        ConfigManager manager = new ConfigManager(null);
        FileConfiguration customDefaults = manager.resolveMessageDefaults("fr");

        YamlConfiguration frenchConfig = new YamlConfiguration();
        frenchConfig.set("actions.errors.details.unload-failed", "echec dechargement");
        frenchConfig.setDefaults(customDefaults);

        assertEquals("echec dechargement", frenchConfig.getString("actions.errors.details.unload-failed"));
        assertEquals("failed to load", frenchConfig.getString("actions.errors.details.load-failed"));
    }

    @Test
    @DisplayName("Verify ActionManager loads English actions when language is en")
    void testActionManagerLoadsEnglishActionsWhenLanguageIsEn() throws Exception {
        ActionManager actionManager = new ActionManager(null, null);
        actionManager.loadActions(null, "en");

        Field cacheField = ActionManager.class.getDeclaredField("actionsCache");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(actionManager);

        assertNotNull(cache);
        List<?> actions = (List<?>) cache.get("actions.errors.no-permission");
        assertNotNull(actions, "actions.errors.no-permission must be cached");
        assertFalse(actions.isEmpty());

        ActionManager.ParsedAction first = (ActionManager.ParsedAction) actions.get(0);
        assertTrue(first.content().contains("Not enough"),
                "English actions must be loaded when language is en, got: " + first.content());
    }

    @Test
    @DisplayName("Verify ActionManager loads Russian actions when language is ru")
    void testActionManagerLoadsRussianActionsWhenLanguageIsRu() throws Exception {
        ActionManager actionManager = new ActionManager(null, null);
        actionManager.loadActions(null, "ru");

        Field cacheField = ActionManager.class.getDeclaredField("actionsCache");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(actionManager);

        assertNotNull(cache);
        List<?> actions = (List<?>) cache.get("actions.errors.no-permission");
        assertNotNull(actions);
        assertFalse(actions.isEmpty());

        ActionManager.ParsedAction first = (ActionManager.ParsedAction) actions.get(0);
        assertFalse(first.content().contains("Not enough"),
                "Russian actions must not contain English string: " + first.content());
    }

    @Test
    @DisplayName("Verify custom action overrides default while missing action preserves default")
    void testActionManagerCustomActionOverridesDefault() throws Exception {
        ActionManager actionManager = new ActionManager(null, null);

        YamlConfiguration custom = new YamlConfiguration();
        custom.set("actions.errors.no-permission", List.of("[message] Custom no perm message"));

        actionManager.loadActions(custom, "en");

        Field cacheField = ActionManager.class.getDeclaredField("actionsCache");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(actionManager);

        List<?> overridden = (List<?>) cache.get("actions.errors.no-permission");
        assertNotNull(overridden);
        assertEquals(1, overridden.size());
        ActionManager.ParsedAction customAction = (ActionManager.ParsedAction) overridden.get(0);
        assertEquals("Custom no perm message", customAction.content());

        List<?> preserved = (List<?>) cache.get("actions.errors.console-not-allowed");
        assertNotNull(preserved, "Omitted action must remain present from defaults");
        ActionManager.ParsedAction defaultAction = (ActionManager.ParsedAction) preserved.get(0);
        assertTrue(defaultAction.content().contains("players"), "Omitted action must use English default");
    }

    @Test
    @DisplayName("Verify chained defaults are acyclic and do not cause StackOverflow on missing key")
    void testAcyclicDefaultsDoesNotStackOverflowOnMissingKey() {
        ConfigManager manager = new ConfigManager(null);

        FileConfiguration ru = manager.resolveMessageDefaults("ru");
        assertNull(ru.getString("non.existent.key.xyz"));

        FileConfiguration en = manager.resolveMessageDefaults("en");
        assertNull(en.getString("non.existent.key.xyz"));

        FileConfiguration custom = manager.resolveMessageDefaults("es");
        assertNull(custom.getString("non.existent.key.xyz"));
    }

    @Test
    @DisplayName("Verify ConfigManager text method uses fallback for deleted key")
    void testConfigManagerTextUsesFallbackForDeletedKey() throws Exception {
        sun.misc.Unsafe unsafe;
        var theUnsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafeField.setAccessible(true);
        unsafe = (sun.misc.Unsafe) theUnsafeField.get(null);

        ConfigManager manager = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);

        YamlConfiguration userConfig = new YamlConfiguration();
        FileConfiguration enDefaults = manager.resolveMessageDefaults("en");
        userConfig.setDefaults(enDefaults);

        Field msgField = ConfigManager.class.getDeclaredField("messagesConfig");
        msgField.setAccessible(true);
        msgField.set(manager, userConfig);

        String text = manager.text("actions.errors.details.unload-failed");
        assertEquals("failed to unload", text);

        String unknown = manager.text("completely.unknown.key");
        assertEquals("completely.unknown.key", unknown);
    }
}
