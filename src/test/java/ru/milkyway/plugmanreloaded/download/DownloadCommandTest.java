package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.commands.sub.DownloadCommand;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DownloadCommandTest {

    @Test
    @DisplayName("Verify required-java placeholder does not produce double plus signs")
    void testRequiredJavaFormatting() throws Exception {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("messages/ru-messages.yml");
        assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        DownloadResult result = DownloadResult.failed(DownloadStatus.INCOMPATIBLE_JAVA, "TestPlugin",
                "Требуется Java 21+, а ваш сервер работает на Java 17");

        String extracted = DownloadCommand.extractRequiredJava(result.message());
        assertEquals("21", extracted);
        assertEquals("17", DownloadCommand.extractRequiredJava("Java 17"));
        assertEquals("16", DownloadCommand.extractRequiredJava("Requires Java 16+, please upgrade"));
        assertEquals("21", DownloadCommand.extractRequiredJava("Network timeout"));

        DownloadCommand cmd = new DownloadCommand(null);
        Method resolveReason = DownloadCommand.class.getDeclaredMethod("resolveReason",
                org.bukkit.configuration.file.FileConfiguration.class, DownloadResult.class, Map.class);
        resolveReason.setAccessible(true);

        Map<String, String> map = new HashMap<>();
        map.put("plugin", result.pluginName());
        map.put("current-java", "17");
        map.put("required-java", extracted);

        String formattedReason = (String) resolveReason.invoke(cmd, config, result, map);
        assertNotNull(formattedReason);
        assertTrue(formattedReason.contains("Java 21+"));
        assertFalse(formattedReason.contains("Java 21++"));
    }

    @Test
    @DisplayName("Verify bootstrapper-installed message explains update folder staging")
    void testBootstrapperMessage() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("messages/ru-messages.yml");
        assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        String bootstrapperMsg = config.getStringList("actions.download.bootstrapper-installed").toString();
        assertTrue(bootstrapperMsg.contains("update/"));
        assertTrue(bootstrapperMsg.contains("перезапуск"));
        assertFalse(bootstrapperMsg.contains("и включен на сервере"));
    }

    @Test
    @DisplayName("Verify not-found message has no stray quotes")
    void testNotFoundMessageTypoFixed() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("messages/ru-messages.yml");
        assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        String notFound = config.getStringList("actions.download.not-found").toString();
        assertFalse(notFound.contains("«не удалось"));
        assertTrue(notFound.contains("не удалось"));
    }

    @Test
    @DisplayName("Verify candidate name extraction supports Spigot URLs with and without slug")
    void testExtractCandidateNameFromUrl() throws Exception {
        DownloadCommand cmd = new DownloadCommand(null);
        Method extractMethod = DownloadCommand.class.getDeclaredMethod("extractCandidateNameFromUrl", String.class);
        extractMethod.setAccessible(true);

        String slugResult = (String) extractMethod.invoke(cmd, "https://www.spigotmc.org/resources/ajleaderboards.105658/");
        assertEquals("ajleaderboards", slugResult);

        PluginSearch.rememberTitleGlobally("105658", "spigot", "ajLeaderboards");
        String noSlugResult = (String) extractMethod.invoke(cmd, "https://www.spigotmc.org/resources/105658/");
        assertEquals("ajLeaderboards", noSlugResult);
    }

    @Test
    @DisplayName("Verify resolveDisplayName converts numeric Spigot IDs to known plugin names")
    void testResolveDisplayName() throws Exception {
        DownloadCommand cmd = new DownloadCommand(null);
        Method resolveMethod = DownloadCommand.class.getDeclaredMethod("resolveDisplayName", String.class);
        resolveMethod.setAccessible(true);

        PluginSearch.rememberTitleGlobally("88888", "spigot", "ViaVersion");
        assertEquals("ViaVersion", resolveMethod.invoke(cmd, "88888"));
        assertEquals("ViaVersion", resolveMethod.invoke(cmd, "spigot:88888"));
        assertEquals("DecentHolograms", resolveMethod.invoke(cmd, "DecentHolograms"));
    }

    @Test
    @DisplayName("Verify global known titles cache remembers and retrieves plugin titles")
    void testGlobalKnownTitlesCache() {
        PluginSearch.rememberTitleGlobally("77777", "spigot", "FastAsyncWorldEdit");
        assertEquals("FastAsyncWorldEdit", PluginSearch.findKnownTitleGlobally("77777", "spigot"));
        assertEquals("FastAsyncWorldEdit", PluginSearch.findKnownTitleGlobally("77777", null));
        assertNull(PluginSearch.findKnownTitleGlobally("nonexistent", "spigot"));
    }
}
