package ru.milkyway.plugmanreloaded.update;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.update.input.ManualSources;
import ru.milkyway.plugmanreloaded.update.input.ManualSources.ManualSourceSession;
import ru.milkyway.plugmanreloaded.update.input.SourceUrlParser;


import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ManualSourceWorkflowTest {

    @BeforeAll
    static void installBukkitStub() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        if (field.get(null) != null) {
            return;
        }

        PluginManager pluginManager = (PluginManager) java.lang.reflect.Proxy.newProxyInstance(
                ManualSourceWorkflowTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null);

        ConsoleCommandSender console = (ConsoleCommandSender) java.lang.reflect.Proxy.newProxyInstance(
                ManualSourceWorkflowTest.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null);

        Logger logger = Logger.getLogger("ManualSourceWorkflowTest");

        Server server = (Server) java.lang.reflect.Proxy.newProxyInstance(
                ManualSourceWorkflowTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> pluginManager;
                    case "getConsoleSender" -> console;
                    case "getLogger" -> logger;
                    case "isPrimaryThread" -> true;
                    default -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                });

        field.set(null, server);
    }

    @Test
    @DisplayName("Verify session debounce rejects inputs within 500ms window")
    void testSessionDebounce() {
        ManualSourceSession session = new ManualSourceSession("Vault", "net.milkbowl.vault.Vault", null);

        assertTrue(session.tryConsumeInput(), "First input consumption should succeed");
        assertFalse(session.tryConsumeInput(), "Immediate second input within 500ms should be rejected");
    }

    @Test
    @DisplayName("Verify cancel keywords are properly identified")
    void testCancelKeywordsDetection() throws Exception {
        Method isCancel = ManualSources.class.getDeclaredMethod("isCancelKeyword", String.class);
        isCancel.setAccessible(true);

        assertTrue((boolean) isCancel.invoke(null, "cancel"));
        assertTrue((boolean) isCancel.invoke(null, "CANCEL"));
        assertTrue((boolean) isCancel.invoke(null, "отмена"));
        assertTrue((boolean) isCancel.invoke(null, "ОТМЕНИТЬ"));
        assertTrue((boolean) isCancel.invoke(null, "exit"));
        assertTrue((boolean) isCancel.invoke(null, "stop"));
        assertTrue((boolean) isCancel.invoke(null, "/cancel"));
        assertTrue((boolean) isCancel.invoke(null, "-"));

        assertFalse((boolean) isCancel.invoke(null, "https://modrinth.com/plugin/vault"));
        assertFalse((boolean) isCancel.invoke(null, "https://github.com/MilkBowl/Vault"));
    }

    @Test
    @DisplayName("Verify UserCatalogWriter writes and merges entries into sources-custom.yml correctly")
    void testUserCatalogWriter(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("sources-custom.yml").toFile();

        SourceCatalog.CatalogSource source1 = new SourceCatalog.CatalogSource(
                "modrinth", "vault", "https://modrinth.com/plugin/vault", Map.of()
        );
        assertTrue(SourceCatalog.writeUserEntry(file, "Vault", "net.milkbowl.vault.Vault", source1));

        YamlConfiguration yaml1 = YamlConfiguration.loadConfiguration(file);
        assertEquals("net.milkbowl.vault.Vault", yaml1.getString("plugins.Vault.main"));
        List<Map<?, ?>> list1 = yaml1.getMapList("plugins.Vault.sources");
        assertEquals(1, list1.size());
        assertEquals("modrinth", list1.get(0).get("id"));
        assertEquals("vault", list1.get(0).get("ref"));

        SourceCatalog.CatalogSource source2 = new SourceCatalog.CatalogSource(
                "github", "MilkBowl/Vault", "https://github.com/MilkBowl/Vault", Map.of()
        );
        assertTrue(SourceCatalog.writeUserEntry(file, "Vault", "net.milkbowl.vault.Vault", source2));

        YamlConfiguration yaml2 = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> list2 = yaml2.getMapList("plugins.Vault.sources");
        assertEquals(2, list2.size(), "Writing a new source for the same plugin should prepend/merge, not overwrite all sources");
        assertEquals("github", list2.get(0).get("id"));
        assertEquals("modrinth", list2.get(1).get("id"));
    }

    @Test
    @DisplayName("Verify SourceUrlParser handles all supported source platforms")
    void testSourceUrlParserPlatforms() {
        SourceUrlParser.ParseResult modrinth = SourceUrlParser.parse("https://modrinth.com/plugin/luckperms");
        assertTrue(modrinth.success());
        assertEquals("modrinth", modrinth.source().sourceId());
        assertEquals("luckperms", modrinth.source().ref());

        SourceUrlParser.ParseResult hangar = SourceUrlParser.parse("https://hangar.papermc.io/PaperMC/Paper");
        assertTrue(hangar.success());
        assertEquals("hangar", hangar.source().sourceId());
        assertEquals("PaperMC/Paper", hangar.source().ref());

        SourceUrlParser.ParseResult spigot = SourceUrlParser.parse("https://www.spigotmc.org/resources/essentialsx.9089/");
        assertTrue(spigot.success());
        assertEquals("spigot", spigot.source().sourceId());
        assertEquals("9089", spigot.source().ref());

        SourceUrlParser.ParseResult github = SourceUrlParser.parse("https://github.com/EssentialsX/Essentials");
        assertTrue(github.success());
        assertEquals("github", github.source().sourceId());
        assertEquals("EssentialsX/Essentials", github.source().ref());

        SourceUrlParser.ParseResult jenkins = SourceUrlParser.parse("https://ci.ender.zone/job/EssentialsX/");
        assertTrue(jenkins.success());
        assertEquals("jenkins", jenkins.source().sourceId());
    }

    @Test
    @DisplayName("Verify SourceUrlParser rejects direct .jar download links with helpful error message")
    void testSourceUrlParserJarRejection() {
        SourceUrlParser.ParseResult res = SourceUrlParser.parse("https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar");
        assertFalse(res.success());
        assertTrue(res.errorReason().contains("direct-jar"), "Отказ должен явно упоминать, что прямые ссылки на .jar не поддерживаются");
    }
}
