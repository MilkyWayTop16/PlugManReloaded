package ru.milkyway.plugmanreloaded.config;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.managers.ActionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ChatButtonFactoryTest {

    @Test
    void testActionManagerAndPlaceholders() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("actions.test", "[message] &#FFFF00Plugin: &f{plugin} (v{version})");

        ActionManager actionManager = new ActionManager(null, null);
        actionManager.loadActions(config);

        assertDoesNotThrow(() -> actionManager.executeRawAction(null, "[message] Hello {plugin}", Map.of("plugin", "Vault")));
    }

    @Test
    void testMultilineActionExecution() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("actions.multiline", "[message] Line1: {plugin}\nLine2: {version}");

        ActionManager actionManager = new ActionManager(null, null);
        actionManager.loadActions(config);

        assertDoesNotThrow(() -> actionManager.executeActions(null, "multiline", Map.of("plugin", "LuckPerms", "version", "5.4.102")));
    }

    @Test
    void testConfirmAllButtonsRendering() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("actions.update.confirm-all-buttons.all.text", "&#22FF00[Обновить все плагины]");
        config.set("actions.update.confirm-all-buttons.all.hover", java.util.List.of("", " &#22FF00▶ &fНажмите, чтобы обновить ({count} шт.) ", ""));
        config.set("actions.update.confirm-all-buttons.cancel.text", "&#FB8808[Отменить]");
        config.set("actions.update.confirm-all-buttons.cancel.hover", java.util.List.of("", " &#FB8808▶ &fНажмите, чтобы отменить ", ""));

        ActionManager actionManager = new ActionManager(null, null);
        actionManager.loadActions(config);

        assertDoesNotThrow(() -> actionManager.executeRawAction(null, "[message] {all-button} {cancel-button}", Map.of("count", "5", "token", "abc123", "action-key", "actions.update.confirm-all")));
    }

    @Test
    void testAllButtonsWithNullConfig() {
        ActionManager actionManager = new ActionManager(null, null);
        assertDoesNotThrow(() -> actionManager.executeRawAction(null, "[message] {all-button} {update-all-button} {single-button} {deps-button} {cancel-button} {confirm-button} {delete-button} {reload-button} {restart-button} {enable-button} {disable-button} {unload-button} {load-button}", Map.of("plugin", "Vault", "count", "3", "available", "3")));
    }

    @Test
    void actionsCacheIgnoresNonActionKeys() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", "1.0");
        config.set("settings.updates.github-token", "secret-token-value");
        config.set("settings.ignored-plugins", java.util.List.of("PlugManReloaded"));
        config.set("actions.test.format", "[message] &#FFFF00Hello");

        ActionManager actionManager = new ActionManager(null, null);
        actionManager.loadActions(config);

        java.lang.reflect.Field cacheField = ActionManager.class.getDeclaredField("actionsCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> cache = (Map<String, ?>) cacheField.get(actionManager);

        assertFalse(cache.containsKey("config-version"),
                "Настройки не являются действиями и не должны попадать в actionsCache");
        assertFalse(cache.containsKey("settings.updates.github-token"),
                "Токен GitHub не должен лежать в карте действий рядом с сообщениями и кнопками");
        assertFalse(cache.containsKey("settings.ignored-plugins"));
        assertFalse(cache.containsKey("updates.github-token"),
                "Свёрнутое имя настройки (без префикса actions.) тоже не должно кэшироваться");

        assertTrue(cache.containsKey("actions.test"), "Реальное действие обязано кэшироваться как обычно");
        assertTrue(cache.containsKey("test"), "И под свёрнутым именем — как обычно");
    }
}
