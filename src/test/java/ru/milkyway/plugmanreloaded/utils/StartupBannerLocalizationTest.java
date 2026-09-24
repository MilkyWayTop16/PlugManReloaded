package ru.milkyway.plugmanreloaded.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupBannerLocalizationTest {

    private static final Path SOURCE = Path.of("src/main/java/ru/milkyway/plugmanreloaded/PlugManReloaded.java");
    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    @Test
    void mainClassHasNoRussianLiteralsLeft() throws Exception {
        List<String> offenders = new ArrayList<>();
        List<String> lines = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            Matcher m = LITERAL.matcher(lines.get(i));
            while (m.find()) {
                if (CYRILLIC.matcher(m.group()).find()) {
                    offenders.add((i + 1) + ": " + trimmed);
                    break;
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "в PlugManReloaded остался русский текст — при language=en владелец увидит его в консоли:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void everyBannerKeyExistsInBothCatalogs() throws Exception {
        YamlConfiguration ru = load("src/main/resources/messages/logs/ru-logs.yml");
        YamlConfiguration en = load("src/main/resources/messages/logs/en-logs.yml");

        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        Pattern keyed = Pattern.compile("\"((?:startup|shutdown)\\.[a-z0-9-]+)\"");
        Matcher m = keyed.matcher(source);

        int found = 0;
        while (m.find()) {
            String key = m.group(1);
            found++;
            assertTrue(ru.contains("logs." + key), "ключа " + key + " нет в ru-logs.yml");
            assertTrue(en.contains("logs." + key), "ключа " + key + " нет в en-logs.yml");
        }
        assertTrue(found >= 18, "ожидались все строки баннера через LogCatalog, найдено ключей: " + found);
    }

    @Test
    void bannerKeysCarryTheSamePlaceholdersInBothLanguages() throws Exception {
        YamlConfiguration ru = load("src/main/resources/messages/logs/ru-logs.yml");
        YamlConfiguration en = load("src/main/resources/messages/logs/en-logs.yml");
        Pattern placeholder = Pattern.compile("\\{([a-z-]+)}");

        for (String section : new String[]{"logs.startup", "logs.shutdown"}) {
            var node = ru.getConfigurationSection(section);
            assertTrue(node != null, "секции " + section + " нет в ru-logs.yml");
            for (String key : node.getKeys(false)) {
                String path = section + "." + key;
                String ruText = ru.getString(path);
                String enText = en.getString(path);
                assertTrue(enText != null, "нет перевода для " + path);

                assertEquals(names(placeholder, ruText), names(placeholder, enText),
                        "разный набор плейсхолдеров у " + path);
                assertTrue(!CYRILLIC.matcher(enText).find(), "английский текст " + path + " содержит кириллицу");
            }
        }
    }

    private static List<String> names(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group(1));
        java.util.Collections.sort(out);
        return out;
    }

    private static YamlConfiguration load(String path) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(Path.of(path), StandardCharsets.UTF_8));
        return config;
    }
}
