package ru.milkyway.plugmanreloaded.configs;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.utils.HexColors;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTagEscapingTest {

    private static final String BS = String.valueOf((char) 92);
    private static final String NL = String.valueOf((char) 10);

    private static YamlConfiguration shippedConfig(String fileName) throws Exception {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Files.newInputStream(Path.of("src/main/resources/messages/" + fileName)), StandardCharsets.UTF_8));
    }

    @Test
    void shippedConfigsStillParseAsYaml() throws Exception {
        for (String fileName : new String[]{"ru-messages.yml", "en-messages.yml"}) {
            YamlConfiguration config = shippedConfig(fileName);
            assertTrue(config.contains("actions.help.main"), fileName + " не разобрался как YAML");
            assertTrue(config.getStringList("actions.help.main").size() > 5);
        }
    }

    @Test
    void backslashEscapingWasRemovedFromMessageFiles() throws Exception {
        for (String fileName : new String[]{"ru-messages.yml", "en-messages.yml"}) {
            String content = Files.readString(Path.of("src/main/resources/messages/" + fileName), StandardCharsets.UTF_8);
            assertFalse(content.contains(BS + "<"),
                    fileName + " содержит " + BS + "< — экранирование угловых скобок больше не нужно "
                            + "(StandaloneTagParser сохраняет нераспознанные теги как обычный текст), не возвращай его");
        }
    }

    @Test
    void helpScreenShowsArgumentPlaceholdersWithoutEscaping() throws Exception {
        for (String fileName : new String[]{"ru-messages.yml", "en-messages.yml"}) {
            java.util.List<String> help = shippedConfig(fileName).getStringList("actions.help.main");
            StringBuilder rendered = new StringBuilder();
            for (String line : help) {
                rendered.append(PlainTextComponentSerializer.plainText()
                        .serialize(HexColors.translateToComponent(line))).append(NL);
            }

            String[] mustAppear = fileName.equals("ru-messages.yml")
                    ? new String[]{"/plm info <Плагин>", "/plm delete <Плагин>", "/plm hotswap <on/off/status>"}
                    : new String[]{"/plm info <Plugin>", "/plm delete <Plugin>", "/plm hotswap <on/off/status>"};

            for (String mustHave : mustAppear) {
                assertTrue(rendered.toString().contains(mustHave),
                        fileName + ": в отрисованной справке пропал аргумент команды: ожидалось «" + mustHave
                                + "», а получилось:" + NL + rendered);
            }
        }
    }

    @Test
    void unknownAngleBracketTagRendersAsLiteralTextWithoutEscaping() {
        String rendered = PlainTextComponentSerializer.plainText()
                .serialize(HexColors.translateToComponent("Аргумент: <Плагин/Файл> — подставь сюда своё"));
        assertTrue(rendered.contains("<Плагин/Файл>"),
                "нераспознанный тег <Плагин/Файл> должен остаться в тексте как есть, а не исчезнуть; получилось: " + rendered);
    }

    @Test
    void realTagsStillWorkAlongsideUnknownOnes() {
        String rendered = PlainTextComponentSerializer.plainText()
                .serialize(HexColors.translateToComponent("<green>Зелёный <Плагин> хвост"));
        assertTrue(rendered.equals("Зелёный <Плагин> хвост"),
                "настоящий тег <green> должен применяться, а неизвестный <Плагин> — остаться текстом; получилось: " + rendered);
    }
}
