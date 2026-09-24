package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCatalogPrefixTest {

    @Test
    void noLogMessageEmbedsItsOwnSeverityPrefix() throws Exception {
        for (String path : new String[]{
                "src/main/resources/messages/logs/ru-logs.yml",
                "src/main/resources/messages/logs/en-logs.yml"}) {
            List<String> offenders = new ArrayList<>();
            List<String> lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("gradient:#ffff00:#ffa500") && line.contains("◆")) {
                    offenders.add((i + 1) + ": " + line.trim());
                }
            }
            assertTrue(offenders.isEmpty(),
                    path + ": эти строки несут встроенный градиентный префикс поверх уже добавляемого "
                            + "снаружи prefix.warn/error/info/success — при печати получится двойной "
                            + "префикс («▶ Внимание | ◆ [Класс] | текст»). Используй просто «[ClassName] текст»:\n  "
                            + String.join("\n  ", offenders));
        }
    }

    @Test
    void warnPrefixPlusCatalogMessageRendersExactlyOnePrefix() {
        String rendered = HexColors.toPlainText(HexColors.translateToComponent(
                LogCatalog.get("prefix.warn") + LogCatalog.get("classloadersanitizer.self-blocked", "plugin", "TestPlugin")));

        long pipeCount = rendered.chars().filter(c -> c == '|').count();
        assertTrue(pipeCount <= 1,
                "ожидался ровно один разделитель «|» в готовой строке лога (степень важности + класс "
                        + "склеиваются в одну связку, а не в цепочку из двух префиксов), получилось: " + rendered);
    }
}
