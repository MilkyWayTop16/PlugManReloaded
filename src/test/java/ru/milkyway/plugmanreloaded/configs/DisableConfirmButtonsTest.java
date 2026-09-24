package ru.milkyway.plugmanreloaded.configs;

import ru.milkyway.plugmanreloaded.utils.ChatButtonFactory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisableConfirmButtonsTest {

    private static YamlConfiguration shippedConfig() throws Exception {
        Path path = Path.of("src/main/resources/messages/ru-messages.yml");
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8));
    }

    private static String label(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c).trim();
    }

    private static String command(Component c) {
        ClickEvent click = c.clickEvent();
        return click == null ? null : String.valueOf(click.value());
    }

    @Test
    void singleButtonForceDisablesTheExactTargetPlugin() throws Exception {
        YamlConfiguration config = shippedConfig();
        Map<String, String> placeholders = Map.of("plugin", "TestPlugin", "cmd-type", "disable");

        Component button = ChatButtonFactory.createButton(config, "{single-button}", placeholders, "disable.confirm");
        String cmd = command(button);

        assertEquals("/plm disable TestPlugin -y", cmd,
                "кнопка «выключить несмотря на риски» обязана слать именно /plm disable <плагин> -f");
        assertTrue(label(button).toLowerCase(java.util.Locale.ROOT).contains("выключ"),
                "подпись кнопки обязана явно говорить про ВЫКЛЮЧЕНИЕ, а не про отмену или что-то ещё: " + label(button));
    }

    @Test
    void cancelButtonCancelsDisableNotSomeOtherAction() throws Exception {
        YamlConfiguration config = shippedConfig();
        Map<String, String> placeholders = Map.of("plugin", "TestPlugin", "cmd-type", "disable");

        Component button = ChatButtonFactory.createButton(config, "{cancel-button}", placeholders, "disable.confirm");
        String cmd = command(button);

        assertEquals("/plm disable cancel TestPlugin", cmd,
                "кнопка отмены обязана слать /plm disable cancel <плагин>, чтобы попасть в ctx.isCancel() у DisableCommand");
    }

    @Test
    void confirmDialogReasonsAreDefinedAndNonBlank() throws Exception {
        YamlConfiguration config = shippedConfig();

        for (String key : new String[]{"has-dependents", "api-provider", "netty"}) {
            String value = config.getString("actions.disable.reasons." + key);
            assertFalse(value == null || value.isBlank(),
                    "actions.disable.reasons." + key + " обязан быть непустой строкой в ru-messages.yml");
        }

        assertTrue(config.contains("actions.disable.confirm"),
                "actions.disable.confirm обязан существовать — это и есть сам диалог подтверждения");
        assertTrue(config.contains("actions.disable.cancelled"),
                "actions.disable.cancelled обязан существовать — иначе handleCancel(ctx, \"disable\") отправит несуществующий ключ");
    }
}
