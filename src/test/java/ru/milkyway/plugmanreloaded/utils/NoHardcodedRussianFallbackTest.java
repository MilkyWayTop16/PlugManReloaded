package ru.milkyway.plugmanreloaded.utils;

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

class NoHardcodedRussianFallbackTest {

    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final List<String> ALLOWED_CYRILLIC_FILES = List.of(
            "update/source/RusPigotSource.java"
    );

    @Test
    void noJavaSourceHasHardcodedCyrillicStringLiterals() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (isAllowed(relative)) continue;

                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;

                    if (relative.endsWith("update/input/ManualSources.java") && line.contains("lower.equals(")) {
                        continue;
                    }

                    Matcher m = STRING_LITERAL.matcher(line);
                    while (m.find()) {
                        if (CYRILLIC.matcher(m.group()).find()) {
                            offenders.add(relative + ":" + (i + 1) + ": " + trimmed);
                            break;
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "В Java-коде остались захардкоженные русские строки — это либо резервный текст "
                        + "«на случай если в конфиге ключа нет», либо жёстко зашитое сообщение мимо каталога "
                        + "messages/*.yml. На английском сервере (language: en) это гарантированно покажет "
                        + "игроку русский текст, если ключ в конфиге отсутствует или конфиг недоступен. "
                        + "Такой текст должен идти только из messages/*.yml и messages/logs/*.yml; "
                        + "Java-дефолт (если вообще нужен) — на английском:\n  "
                        + String.join("\n  ", offenders));
    }

    private static boolean isAllowed(String relativePath) {
        for (String allowed : ALLOWED_CYRILLIC_FILES) {
            if (relativePath.endsWith(allowed)) return true;
        }
        return false;
    }
}
