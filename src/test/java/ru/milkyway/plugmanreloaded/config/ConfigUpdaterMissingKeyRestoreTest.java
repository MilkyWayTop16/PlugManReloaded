package ru.milkyway.plugmanreloaded.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.configs.ConfigUpdater;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * До релиза config-version в config.yml/messages/*.yml намеренно заморожен на "1.0" (AGENTS.md,
 * "не трогать без явной просьбы"). Старый ConfigUpdater.update() ставил доливку недостающих ключей
 * ЗА ТЕМ ЖЕ ранним "if (compareVersions(...) >= 0) return;", что и версию — а раз версия
 * пользователя и версия шаблона всегда совпадают ("1.0" == "1.0"), доливка не срабатывала НИКОГДА,
 * даже если ключ физически отсутствует в файле. На практике это означало: если владелец сервера
 * (или сам плагин при живом тестировании) случайно удалил ключ из messages/*.yml, он не
 * восстанавливался — плагин тихо использовал жёстко зашитый в код запасной текст (часто русский,
 * даже при language: en). Тест ловит именно этот сценарий: версия совпадает, но ключ пропал.
 */
class ConfigUpdaterMissingKeyRestoreTest {

    @Test
    void missingKeyIsRestoredEvenWhenVersionAlreadyMatches(@TempDir Path tempDir) throws Exception {
        File configFile = tempDir.resolve("config.yml").toFile();
        // version совпадает с дефолтной "1.0" — именно та ветка, которую старый код считал
        // "нечего обновлять" и выходил раньше, чем успевал долить недостающие ключи
        String brokenUserConfig = """
                config-version: "1.0"
                settings:
                  bstats:
                    enabled: false
                """;
        Files.writeString(configFile.toPath(), brokenUserConfig);

        ConfigUpdater updater = new ConfigUpdater(null);
        updater.update(configFile, "config.yml");

        String after = Files.readString(configFile.toPath());
        // safe-mode целиком отсутствовал в исходном файле — обязан появиться
        assertTrue(after.contains("safe-mode:"),
                "недостающая секция settings.safe-mode не была долита, хотя версия конфига совпадает с дефолтной");
        assertTrue(after.contains("enabled: true"),
                "значение по умолчанию для safe-mode.enabled не было долито");
        // а то, что реально было в пользовательском файле, обязано остаться нетронутым
        assertTrue(after.contains("enabled: false"),
                "пользовательское значение bstats.enabled=false было затёрто восстановлением недостающих ключей");
        // версия при этом НЕ обязана бампаться — она и так совпадала с дефолтной (SnakeYAML
        // перезаписывает явную строку как config-version: '1.0', это нормально, не баг)
        assertTrue(after.contains("config-version: '1.0'") || after.contains("config-version: \"1.0\"") || after.contains("config-version: 1.0"),
                "версия конфига неожиданно изменилась, хотя она уже совпадала с дефолтной: " + after.lines().filter(l -> l.contains("config-version")).findFirst().orElse("???"));
    }

    @Test
    void nothingMissingAndVersionMatchesMeansNoWriteAtAll(@TempDir Path tempDir) throws Exception {
        // Регресс-страховка на противоположный случай: если реально нечего восстанавливать,
        // ConfigUpdater не обязан трогать файл или создавать бэкап при каждом старте плагина.
        File configFile = tempDir.resolve("config.yml").toFile();
        String fullConfig = Files.readString(Path.of("src/main/resources/config.yml"));
        Files.writeString(configFile.toPath(), fullConfig);

        ConfigUpdater updater = new ConfigUpdater(null);
        updater.update(configFile, "config.yml");

        assertEquals(fullConfig, Files.readString(configFile.toPath()),
                "полностью актуальный конфиг не должен переписываться вообще");
        assertFalse(tempDir.resolve("backups/configs").toFile().exists(),
                "бэкап не должен создаваться, если реально нечего чинить");
    }
}
