package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class DependencyResolverTest {

    @BeforeAll
    static void installBukkitStub() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        if (field.get(null) != null) {
            return;
        }

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                DependencyResolverTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> method.getName().equals("getPlugins") ? new Plugin[0] : null);

        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(
                DependencyResolverTest.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> null);

        Logger logger = Logger.getLogger("DependencyResolverTest");

        Server server = (Server) Proxy.newProxyInstance(
                DependencyResolverTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> pluginManager;
                    case "getConsoleSender" -> console;
                    case "getLogger" -> logger;
                    default -> null;
                });

        field.set(null, server);
    }

    private static File writeJar(Path dir, String name, String depend) throws Exception {
        File jar = dir.resolve(name + ".jar").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            StringBuilder yml = new StringBuilder();
            yml.append("name: ").append(name).append('\n');
            yml.append("main: test.").append(name).append('\n');
            yml.append("version: 1.0\n");
            if (depend != null) {
                yml.append("depend: [").append(depend).append("]\n");
            }
            zip.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    private static SearchResultEntry entry(String title, String slug) {
        return new SearchResultEntry("modrinth", slug, title, "author", "1.0", "description",
                "https://modrinth.com/plugin/" + slug, null, 1000, 10, 100.0,
                Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true);
    }

    private static final class FixedSearchEngine extends PluginSearch {
        private final List<SearchResultEntry> hits;

        FixedSearchEngine(List<SearchResultEntry> hits) {
            super(null, null, null);
            this.hits = hits;
        }

        @Override
        public List<SearchResultEntry> search(String query, String preferredSource, int limit) {
            return hits;
        }
    }

    @Test
    public void testDependenciesAreReadFromJar(@TempDir Path dir) throws Exception {
        File target = writeJar(dir, "Target", "Vault");
        DependencyResolver resolver = new DependencyResolver(null,
                new FixedSearchEngine(List.of(entry("Vault", "vault"))), null);

        DependencyTree tree = resolver.resolve(target, entry("Target", "target"), false);

        assertEquals(1, tree.requiredDependencies().size(),
                "Зависимость Vault обязана быть прочитана из plugin.yml");
        assertEquals("Vault", tree.requiredDependencies().get(0).title());
        assertTrue(tree.hasMissing());
    }

    @Test
    public void testTransitiveDependenciesAreResolved(@TempDir Path dir) throws Exception {
        File charlie = writeJar(dir, "Charlie", null);
        File bravo = writeJar(dir, "Bravo", "Charlie");
        File alpha = writeJar(dir, "Alpha", "Bravo");

        Map<String, File> jars = new HashMap<>();
        jars.put("Bravo", bravo);
        jars.put("Charlie", charlie);

        DependencyResolver resolver = new DependencyResolver(null,
                new FixedSearchEngine(List.of(entry("Bravo", "bravo"), entry("Charlie", "charlie"))),
                null, e -> jars.get(e.title()));

        DependencyTree tree = resolver.resolve(alpha, entry("Alpha", "alpha"), false);

        List<String> titles = tree.requiredDependencies().stream().map(SearchResultEntry::title).toList();
        assertEquals(List.of("Charlie", "Bravo"), titles);
    }

    @Test
    public void testCycleIsDetected(@TempDir Path dir) throws Exception {
        File yankee = writeJar(dir, "Yankee", "Xray");
        File xray = writeJar(dir, "Xray", "Yankee");

        Map<String, File> jars = new HashMap<>();
        jars.put("Yankee", yankee);
        jars.put("Xray", xray);

        DependencyResolver resolver = new DependencyResolver(null,
                new FixedSearchEngine(List.of(entry("Yankee", "yankee"), entry("Xray", "xray"))),
                null, e -> jars.get(e.title()));

        DependencyTree tree = resolver.resolve(xray, entry("Xray", "xray"), false);

        assertTrue(tree.hasCycles(), "Цикл Xray -> Yankee -> Xray обязан быть обнаружен");
        assertNotNull(tree.cycleDetails());
        assertFalse(tree.isFullyResolvable(), "При цикле дерево не считается разрешимым");
    }

    @Test
    public void testWrongSearchHitIsRejected(@TempDir Path dir) throws Exception {
        File target = writeJar(dir, "Target", "Vault");
        DependencyResolver resolver = new DependencyResolver(null,
                new FixedSearchEngine(List.of(entry("VaultAPI", "vaultapi"), entry("Vault Addon", "vault-addon"))),
                null);

        DependencyTree tree = resolver.resolve(target, entry("Target", "target"), false);

        assertTrue(tree.requiredDependencies().isEmpty(),
                "Чужой проект не должен подставляться вместо Vault");
        assertEquals(List.of("Vault"), tree.unresolvableDependencies());
        assertFalse(tree.isFullyResolvable());
    }

    @Test
    public void testCorrectHitIsPickedEvenIfNotFirst(@TempDir Path dir) throws Exception {
        File target = writeJar(dir, "Target", "Vault");
        DependencyResolver resolver = new DependencyResolver(null,
                new FixedSearchEngine(List.of(entry("VaultAPI", "vaultapi"), entry("Vault", "vault"))),
                null);

        DependencyTree tree = resolver.resolve(target, entry("Target", "target"), false);

        assertEquals(1, tree.requiredDependencies().size());
        assertEquals("Vault", tree.requiredDependencies().get(0).title(),
                "Выбирается совпавший по имени, а не первый в выдаче");
    }

    @Test
    public void testCatalogPicksMostTrustedSourceRegardlessOfFileOrder(@TempDir Path dir) throws Exception {
        File target = writeJar(dir, "Target", "SomeLib");

        File customCatalog = dir.resolve("sources-custom.yml").toFile();
        java.nio.file.Files.writeString(customCatalog.toPath(), """
                plugins:
                  SomeLib:
                    sources:
                      - id: spigot
                        ref: "99999"
                        url: "https://www.spigotmc.org/resources/99999"
                      - id: github
                        ref: "someauthor/SomeLib"
                        url: "https://github.com/someauthor/SomeLib"
                """);
        SourceCatalog catalog = new SourceCatalog(customCatalog);

        DependencyResolver resolver = new DependencyResolver(null, new FixedSearchEngine(List.of()), catalog);
        DependencyTree tree = resolver.resolve(target, entry("Target", "target"), false);

        assertEquals(1, tree.requiredDependencies().size());
        SearchResultEntry resolved = tree.requiredDependencies().get(0);
        assertEquals("github", resolved.sourceId(),
                "В файле spigot указан первым, но github надёжнее — должен выбираться github, "
                        + "а не первая по порядку запись каталога");
        assertEquals("someauthor/SomeLib", resolved.projectId());
    }

    @Test
    public void testAllReasonKeysExistInDefaultConfig() {
        InputStream stream = getClass().getResourceAsStream("/messages/ru-messages.yml");
        assertNotNull(stream, "Файл сообщений ru-messages.yml должен существовать в ресурсах");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        String[] expectedKeys = {
                "incompatible-java",
                "invalid-jar",
                "invalid-manifest",
                "hash-mismatch",
                "download-failed",
                "missing-deps",
                "circular-deps",
                "locked",
                "rolled-back",
                "activation-failed",
                "write-failed",
                "rate-limited"
        };

        for (String key : expectedKeys) {
            String path = "actions.download.reasons." + key;
            assertTrue(config.contains(path), "Секция " + path + " обязана присутствовать в ru-messages.yml");
            String val = config.getString(path);
            assertNotNull(val);
            assertFalse(val.isBlank());
            assertFalse(val.endsWith("."), "Сообщение в " + path + " не должно заканчиваться точкой: " + val);
        }
    }

    @Test
    public void testReasonPlaceholderReplacement() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("actions.download.reasons.incompatible-java", "Плагину требуется Java {required-java}+, а на сервере {current-java}");
        config.set("actions.download.reasons.missing-deps", "Не найдены зависимости: {deps}");

        Map<String, String> p = new HashMap<>();
        p.put("required-java", "21");
        p.put("current-java", "17");
        p.put("deps", "Vault, ProtocolLib");

        String javaReason = config.getString("actions.download.reasons.incompatible-java");
        for (Map.Entry<String, String> e : p.entrySet()) {
            javaReason = javaReason.replace("{" + e.getKey() + "}", e.getValue());
        }
        assertEquals("Плагину требуется Java 21+, а на сервере 17", javaReason);

        String depsReason = config.getString("actions.download.reasons.missing-deps");
        for (Map.Entry<String, String> e : p.entrySet()) {
            depsReason = depsReason.replace("{" + e.getKey() + "}", e.getValue());
        }
        assertEquals("Не найдены зависимости: Vault, ProtocolLib", depsReason);
    }

    @Test
    public void testDownloadSuggestionsConfig() {
        InputStream stream = getClass().getResourceAsStream("/config.yml");
        assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertTrue(config.getBoolean("settings.download.suggestions.enabled", false), "suggestions.enabled по умолчанию должно быть true");
        assertTrue(config.getBoolean("settings.download.suggestions.hide-downloaded", false), "suggestions.hide-downloaded по умолчанию должно быть true");

        List<String> plugins = config.getStringList("settings.download.suggestions.plugins");
        assertNotNull(plugins);
        assertTrue(plugins.size() >= 15, "Список популярных плагинов должен содержать не менее 15 плагинов");
        assertTrue(plugins.contains("LuckPerms"));
        assertTrue(plugins.contains("Vault"));
        assertTrue(plugins.contains("PlaceholderAPI"));
        assertTrue(plugins.contains("WorldGuard"));
        assertTrue(plugins.contains("EssentialsX"));
    }

    @Test
    public void testConfirmSingleActionConfigExists() {
        InputStream stream = getClass().getResourceAsStream("/messages/ru-messages.yml");
        assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        List<String> confirmSingle = config.getStringList("actions.download.confirm-single");
        assertNotNull(confirmSingle);
        assertFalse(confirmSingle.isEmpty(), "actions.download.confirm-single must exist");

        String buttonText = config.getString("actions.download.buttons.confirm-single.text");
        assertNotNull(buttonText, "actions.download.buttons.confirm-single.text must exist");
    }
    @Test
    public void testResolvePrioritizesDeclaredJarNameOverNumericTitle(@TempDir Path dir) throws Exception {
        File jar = writeJar(dir, "MyRealPlugin", null);
        DependencyResolver resolver = new DependencyResolver(null, null, null);

        SearchResultEntry entry = new SearchResultEntry(
                "spigot", "105658", "105658", "Author", "", "",
                "https://www.spigotmc.org/resources/105658", null, 100, 5, 0.0,
                Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );

        DependencyTree tree = resolver.resolve(jar, entry, false);
        assertEquals("MyRealPlugin", tree.targetPluginName());
    }
}
