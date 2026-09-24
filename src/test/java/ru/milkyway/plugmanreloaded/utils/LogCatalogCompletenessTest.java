package ru.milkyway.plugmanreloaded.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCatalogCompletenessTest {

    private static final Pattern CALL = Pattern.compile(
            "(?:\\bLog\\.(?:debug|warn|error|info|success|log|debugPlain)|\\bplugin\\.(?:log|warn|error|console|success))"
                    + "\\(\\s*\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_-]+)+)\""
    );

    @Test
    void everyLogCallLiteralExistsInTheCatalog() throws IOException {
        YamlConfiguration catalog = new YamlConfiguration();
        try {
            catalog.loadFromString(Files.readString(Path.of("src/main/resources/messages/logs/ru-logs.yml"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException(e);
        }

        List<String> missing = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = CALL.matcher(lines.get(i));
                    while (m.find()) {
                        String key = m.group(1);
                        if (!catalog.contains("logs." + key)) {
                            missing.add(file + ":" + (i + 1) + " -> logs." + key);
                        }
                    }
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "Эти ключи вызываются из кода, но отсутствуют в ru-logs.yml — LogCatalog.get() вернёт "
                        + "сам ключ, и в консоль уйдёт его буквальный текст вместо сообщения:\n  "
                        + String.join("\n  ", missing));
    }
}
