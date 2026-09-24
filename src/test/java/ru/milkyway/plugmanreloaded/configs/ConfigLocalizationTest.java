package ru.milkyway.plugmanreloaded.configs;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLocalizationTest {

    private static final String RU_PATH = "src/main/resources/config.yml";
    private static final String EN_PATH = "src/main/resources/config-en.yml";
    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");
    }

    private static FileConfiguration parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception e) {
            throw new AssertionError("получился невалидный YAML: " + e.getMessage() + "\n" + text, e);
        }
        return config;
    }

    private static Set<String> leafKeys(FileConfiguration config) {
        Set<String> leaves = new LinkedHashSet<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) leaves.add(key);
        }
        return leaves;
    }

    @Test
    void bothTemplatesDescribeTheSameConfig() throws Exception {
        FileConfiguration ru = parse(read(RU_PATH));
        FileConfiguration en = parse(read(EN_PATH));

        assertEquals(ru.getKeys(true), en.getKeys(true),
                "структура шаблонов разошлась — у переводов должны быть одни и те же ключи");

        for (String key : leafKeys(ru)) {
            if (key.equals("settings.language")) continue;
            assertEquals(ru.get(key), en.get(key),
                    "значение по умолчанию у «" + key + "» разное в ru/en шаблонах — параметры переводить нельзя");
        }

        assertEquals("ru", ru.getString("settings.language"));
        assertEquals("en", en.getString("settings.language"));
    }

    @Test
    void englishTemplateHasNoCyrillicAndRussianOneDoes() throws Exception {
        String en = read(EN_PATH);
        List<String> cyrillicLines = new ArrayList<>();
        String[] lines = en.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (CYRILLIC.matcher(lines[i]).find()) cyrillicLines.add((i + 1) + ": " + lines[i]);
        }
        assertTrue(cyrillicLines.isEmpty(),
                "в английском шаблоне остался русский текст:\n  " + String.join("\n  ", cyrillicLines));

        assertTrue(CYRILLIC.matcher(read(RU_PATH)).find(), "русский шаблон обязан быть русским");
    }

    @Test
    void templatesHaveNoInlineComments() throws Exception {
        for (String path : new String[]{RU_PATH, EN_PATH}) {
            String[] lines = read(path).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int hash = lines[i].indexOf(" #");
                assertTrue(hash < 0,
                        path + ":" + (i + 1) + " — комментарий в конце строки значения не поддерживается "
                                + "перерисовкой и будет потерян: " + lines[i]);
            }
        }
    }

    @Test
    void renderingATemplateWithItsOwnValuesChangesNothing() throws Exception {
        for (String path : new String[]{RU_PATH, EN_PATH}) {
            String text = read(path);
            assertEquals(text, ConfigLocalizer.render(text, parse(text)),
                    path + ": перерисовка нетронутого конфига обязана давать байт-в-байт тот же файл");
        }
    }

    @Test
    void switchingLanguageKeepsEveryUserValue() throws Exception {
        String ruText = read(RU_PATH);
        FileConfiguration user = parse(ruText);

        user.set("settings.language", "en");
        user.set("settings.logs-in-console.enable", true);
        user.set("settings.update-checker.periodic-interval-hours", 12);
        user.set("settings.hot-swap.debounce-ms", 1234);
        user.set("settings.updates.notify-mode", "console");
        user.set("settings.updates.github-token", "ghp_totallyRealToken123");
        user.set("settings.backups.keep-days", 30);
        user.set("settings.ignored-plugins", List.of("PlugManReloaded", "MyOwnPlugin"));
        user.set("settings.download.suggestions.plugins", List.of("LuckPerms", "Vault"));

        String rendered = ConfigLocalizer.render(read(EN_PATH), user);
        FileConfiguration result = parse(rendered);

        assertEquals("en", result.getString("settings.language"));
        assertTrue(result.getBoolean("settings.logs-in-console.enable"));
        assertEquals(12, result.getInt("settings.update-checker.periodic-interval-hours"));
        assertEquals(1234, result.getInt("settings.hot-swap.debounce-ms"));
        assertEquals("console", result.getString("settings.updates.notify-mode"));
        assertEquals("ghp_totallyRealToken123", result.getString("settings.updates.github-token"));
        assertEquals(30, result.getInt("settings.backups.keep-days"));
        assertEquals(List.of("PlugManReloaded", "MyOwnPlugin"), result.getStringList("settings.ignored-plugins"));
        assertEquals(List.of("LuckPerms", "Vault"), result.getStringList("settings.download.suggestions.plugins"));

        assertEquals(leafKeys(user), leafKeys(result), "после перерисовки набор параметров изменился");
        assertFalse(CYRILLIC.matcher(rendered).find(), "после перехода на en в конфиге остался русский текст");
        assertTrue(rendered.contains("# Logging settings"), "английские комментарии не подставились");
    }

    @Test
    void roundTripBetweenLanguagesKeepsValues() throws Exception {
        String ruText = read(RU_PATH);
        String enText = read(EN_PATH);

        FileConfiguration user = parse(ruText);
        user.set("settings.safe-mode.enabled", false);
        user.set("settings.updates.cache-ttl-hours", 9);
        user.set("settings.unsafe-to-unload", List.of("ProtocolLib", "MyNetworkPlugin"));

        String toEnglish = ConfigLocalizer.render(enText, user);
        String backToRussian = ConfigLocalizer.render(ruText, parse(toEnglish));

        FileConfiguration result = parse(backToRussian);
        assertFalse(result.getBoolean("settings.safe-mode.enabled"));
        assertEquals(9, result.getInt("settings.updates.cache-ttl-hours"));
        assertEquals(List.of("ProtocolLib", "MyNetworkPlugin"), result.getStringList("settings.unsafe-to-unload"));

        assertTrue(CYRILLIC.matcher(backToRussian).find(), "вернувшись на ru, комментарии обязаны стать русскими");
        assertEquals(leafKeys(user), leafKeys(result));
    }

    @Test
    void awkwardStringValuesSurviveEscaping() throws Exception {
        FileConfiguration user = parse(read(RU_PATH));
        String nasty = "he said \"hi\" \\ and left: done # not-a-comment";
        user.set("settings.updates.github-token", nasty);

        FileConfiguration result = parse(ConfigLocalizer.render(read(EN_PATH), user));
        assertEquals(nasty, result.getString("settings.updates.github-token"));
    }

    @Test
    void templateResourcesAreResolvedByLanguage() {
        assertEquals("config.yml", ConfigLocalizer.templateResourceFor("ru"));
        assertEquals("config-en.yml", ConfigLocalizer.templateResourceFor("en"));
        assertEquals("config-en.yml", ConfigLocalizer.templateResourceFor("  EN "));
        assertEquals(null, ConfigLocalizer.templateResourceFor("de"),
                "для чужого языка шаблона нет — конфиг трогать нельзя");
        assertEquals(null, ConfigLocalizer.templateResourceFor(null));
    }
}
