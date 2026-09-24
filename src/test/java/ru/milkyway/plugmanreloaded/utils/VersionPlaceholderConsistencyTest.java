package ru.milkyway.plugmanreloaded.utils;

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

class VersionPlaceholderConsistencyTest {

    @Test
    void cleanVersionStripsOnlyTheLeadingVAndNeverTouchesTheRest() {
        assertEquals("2.5", PluginMetaHelper.cleanVersion("v2.5"));
        assertEquals("2.5", PluginMetaHelper.cleanVersion("V2.5"));
        assertEquals("2.5", PluginMetaHelper.cleanVersion("  v2.5  "));
        assertEquals("1.0", PluginMetaHelper.cleanVersion(null));
        assertEquals("1.0", PluginMetaHelper.cleanVersion("   "));

        assertEquals("2.0-preview", PluginMetaHelper.cleanVersion("v2.0-preview"),
                "«v» внутри строки обязана оставаться на месте: наивный replace(\"v\",\"\") превращал "
                        + "тег v2.0-preview в «2.0-preiew», ломая и показ версии, и сравнение версий");
        assertEquals("1.0-dev", PluginMetaHelper.cleanVersion("v1.0-dev"));
        assertEquals("3.7-beta", PluginMetaHelper.cleanVersion("3.7-beta"));
    }

    private static final java.util.Map<String, String> RAW_VERSION_ALLOWED = java.util.Map.of(
            "UpdateService.java",
            "строит заголовок User-Agent — там нужна ровно та строка версии, что объявлена в plugin.yml",
            "IdentityScanner.java",
            "заполняет PluginIdentity.currentVersion: её потребители либо сравнивают через VersionCompare "
                    + "(он сам срезает ведущую v), либо ищут подстроку версии в ИМЕНИ ФАЙЛА jar "
                    + "(UpdateInstaller.determineTargetFile), а к показу её чистит UpdateCommand.cleanVersion",
            "AbstractSubCommand.java",
            "сразу же применяет replaceFirst(\"^[vV]+\", \"\") к прочитанному значению",
            "PluginMetaHelper.java",
            "это и есть канонический чистильщик версии"
    );

    @Test
    void rawDescriptionVersionIsNeverReadOutsideTheAllowlist() throws Exception {
        Pattern rawVersionRead = Pattern.compile("getDescription\\s*\\(\\s*\\)\\s*\\.\\s*getVersion\\s*\\(\\s*\\)");

        List<String> offenders = new ArrayList<>();
        try (var stream = Files.walk(Path.of("src/main/java"))) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String fileName = path.getFileName().toString();
                if (RAW_VERSION_ALLOWED.containsKey(fileName)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    Matcher matcher = rawVersionRead.matcher(line);
                    if (matcher.find() && !line.contains("replaceFirst") && !line.contains("PluginMetaHelper")) {
                        offenders.add(fileName + ":" + (i + 1) + " → " + line.trim());
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "Эти места читают версию плагина напрямую из дескриптора вместо "
                        + "PluginMetaHelper.getVersion(...). Шаблоны config.yml пишут «v{version}», поэтому "
                        + "у плагина, объявившего «version: v2.5», игрок увидит «vv2.5». Если сырая версия "
                        + "нужна по смыслу — добавь файл в RAW_VERSION_ALLOWED вместе с обоснованием:\n  "
                        + String.join("\n  ", offenders));
    }
}
