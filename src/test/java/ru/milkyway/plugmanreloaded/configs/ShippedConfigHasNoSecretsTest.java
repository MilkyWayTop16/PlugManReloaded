package ru.milkyway.plugmanreloaded.configs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippedConfigHasNoSecretsTest {

    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    private static final List<Pattern> SECRET_SHAPES = List.of(
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{20,}"),
            Pattern.compile("gho_[A-Za-z0-9]{20,}"),
            Pattern.compile("github_[A-Za-z0-9]{30,}"),
            Pattern.compile("(?i)bearer\s+[A-Za-z0-9._-]{20,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}")
    );

    @Test
    void shippedConfigContainsNoCredentialLikeValues() throws Exception {
        String text = Files.readString(CONFIG, StandardCharsets.UTF_8);
        List<String> hits = new ArrayList<>();
        for (Pattern shape : SECRET_SHAPES) {
            Matcher matcher = shape.matcher(text);
            while (matcher.find()) {
                String found = matcher.group();
                String masked = found.substring(0, Math.min(12, found.length())) + "...";
                hits.add(masked + " (шаблон " + shape.pattern() + ")");
            }
        }
        assertTrue(hits.isEmpty(),
                "В config.yml лежит значение, похожее на настоящий токен. Этот файл уходит внутри jar "
                        + "каждому пользователю — считай, что секрет опубликован. Убери значение и ОТЗОВИ токен: "
                        + hits);
    }

    @Test
    void credentialFieldsShipEmpty() throws Exception {
        List<String> lines = Files.readAllLines(CONFIG, StandardCharsets.UTF_8);
        List<String> filled = new ArrayList<>();
        Pattern field = Pattern.compile("^\s*([a-z0-9-]*(?:token|secret|password|api-key|apikey))\s*:\s*(.+)$");

        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = field.matcher(lines.get(i));
            if (!matcher.find()) continue;
            String value = matcher.group(2).trim();
            if (value.equals("\"\"") || value.equals("''") || value.isEmpty()) continue;
            filled.add((i + 1) + ": " + matcher.group(1));
        }

        assertEquals(List.of(), filled,
                "Поля для учётных данных обязаны уезжать в релиз ПУСТЫМИ — их заполняет владелец сервера "
                        + "у себя, а не автор в исходнике");
    }
}
