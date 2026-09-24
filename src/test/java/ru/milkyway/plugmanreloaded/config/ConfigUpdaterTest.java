package ru.milkyway.plugmanreloaded.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.configs.ConfigUpdater;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigUpdaterTest {

    @Test
    void testCommentPreservingMigrationAndBackupRotation(@TempDir Path tempDir) throws Exception {
        File configFile = tempDir.resolve("config.yml").toFile();
        String oldUserConfig = """
                config-version: "0.8"
                settings:
                  bstats:
                    enabled: false
                """;
        Files.writeString(configFile.toPath(), oldUserConfig);

        ConfigUpdater updater = new ConfigUpdater(null);
        updater.update(configFile, "config.yml");

        String updatedContent = Files.readString(configFile.toPath());
        assertTrue(updatedContent.contains("settings:"));
        assertTrue(updatedContent.contains("bstats:"));
        assertTrue(updatedContent.contains("enabled: false"));
        assertTrue(updatedContent.contains("update-checker:"));
        assertTrue(updatedContent.contains("Система проверки обновлений плагина"));

        File backupDir = tempDir.resolve("backups/configs").toFile();
        assertTrue(backupDir.exists());
        File[] backups = backupDir.listFiles((dir, name) -> name.endsWith(".bak"));
        assertNotNull(backups);
        assertEquals(1, backups.length);
    }

    @Test
    void testNoRewriteWhenVersionMatches(@TempDir Path tempDir) throws Exception {
        // Фикстура обязана быть НАСТОЯЩИМ полным конфигом (а не парой ключей) — иначе тест
        // «проходит» просто потому, что ConfigUpdater ничего по-настоящему не проверяет.
        // Раньше так и было: ранний return по версии скрывал, что доливка недостающих ключей
        // вообще не запускалась (см. ConfigUpdaterMissingKeyRestoreTest).
        File configFile = tempDir.resolve("config.yml").toFile();
        String fullDefault = Files.readString(Path.of("src/main/resources/config.yml"));
        String currentConfig = flipFirstBstatsEnabled(fullDefault);
        assertTrue(currentConfig.contains("enabled: false") && !currentConfig.equals(fullDefault),
                "тестовая правка не применилась — фикстура теста сломана");
        Files.writeString(configFile.toPath(), currentConfig);

        ConfigUpdater updater = new ConfigUpdater(null);
        updater.update(configFile, "config.yml");

        String contentAfter = Files.readString(configFile.toPath());
        assertEquals(currentConfig, contentAfter,
                "полностью актуальный конфиг не должен переписываться, даже если версия и так совпадает с дефолтной");

        File backupDir = tempDir.resolve("backups/configs").toFile();
        assertFalse(backupDir.exists());
    }

    private static String flipFirstBstatsEnabled(String yaml) {
        String[] lines = yaml.split("\n", -1);
        boolean inBstats = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.equals("bstats:")) {
                inBstats = true;
                continue;
            }
            if (inBstats && trimmed.startsWith("enabled:")) {
                lines[i] = lines[i].replace("enabled: true", "enabled: false");
                break;
            }
        }
        return String.join("\n", lines);
    }
}
