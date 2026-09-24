package ru.milkyway.plugmanreloaded.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class UserCatalogWriterTest {

    @Test
    void testUserCatalogWriterAtomicSave(@TempDir Path tempDir) throws Exception {
        File customFile = tempDir.resolve("sources-custom.yml").toFile();

        boolean written = SourceCatalog.writeUserEntry(
                customFile,
                "TestPlugin",
                "com.example.TestPlugin",
                new SourceCatalog.CatalogSource("github", "user/testplugin", "https://github.com/user/testplugin", Map.of())
        );

        assertTrue(written);
        assertTrue(customFile.exists());

        String content = Files.readString(customFile.toPath());
        assertTrue(content.contains("TestPlugin:"));
        assertTrue(content.contains("com.example.TestPlugin"));
        assertTrue(content.contains("user/testplugin"));

        boolean writtenSecond = SourceCatalog.writeUserEntry(
                customFile,
                "TestPlugin",
                "com.example.TestPlugin",
                new SourceCatalog.CatalogSource("modrinth", "testplugin-modrinth", "https://modrinth.com/plugin/testplugin", Map.of())
        );
        assertTrue(writtenSecond);

        String contentSecond = Files.readString(customFile.toPath());
        assertTrue(contentSecond.contains("github"));
        assertTrue(contentSecond.contains("modrinth"));
        assertTrue(contentSecond.contains("user/testplugin"));
        assertTrue(contentSecond.contains("testplugin-modrinth"));
    }

    // Регрессия: SourceCatalog.createUserCatalog/UserCatalogWriter.write раньше ВСЕГДА брали
    // заголовок/шапку из русского "sources-custom.yml", даже если language: en в config.yml —
    // "sources-custom-en.yml" при этом просто лежал неиспользуемым в ресурсах.
    @Test
    void autoCreatedCatalogRespectsConfiguredLanguage(@TempDir Path tempDir) {
        File ruFile = tempDir.resolve("ru/sources-custom.yml").toFile();
        new SourceCatalog(ruFile, "ru");
        assertTrue(ruFile.isFile(), "Файл должен быть автосоздан из встроенного шаблона");
        String ruContent = readSafely(ruFile);
        assertTrue(ruContent.contains("Пользовательские источники"),
                "language=ru должен брать шаблон sources-custom.yml (русские комментарии)");

        File enFile = tempDir.resolve("en/sources-custom.yml").toFile();
        new SourceCatalog(enFile, "en");
        assertTrue(enFile.isFile(), "Файл должен быть автосоздан из встроенного шаблона");
        String enContent = readSafely(enFile);
        assertTrue(enContent.contains("Custom plugin update sources"),
                "language=en должен брать шаблон sources-custom-en.yml (английские комментарии), "
                        + "а не всегда русский");
    }

    @Test
    void writerCopiesHeaderMatchingLanguage(@TempDir Path tempDir) {
        File enFile = tempDir.resolve("sources-custom.yml").toFile();
        boolean written = SourceCatalog.writeUserEntry(enFile, "TestPlugin", "com.example.TestPlugin",
                new SourceCatalog.CatalogSource("github", "user/testplugin", null, Map.of()), "en");
        assertTrue(written);
        String content = readSafely(enFile);
        assertTrue(content.contains("Custom plugin update sources"),
                "SourceCatalog.writeUserEntry(..., \"en\") должен скопировать английскую шапку, а не русскую");
    }

    @Test
    void resolveFileIsSingleSourceOfTruth() {
        // Раньше этот же File(dataFolder, "sources-custom.yml") был независимо продублирован
        // в UpdateService/ManualSources/PluginDownloader — теперь все три
        // обязаны идти через SourceCatalog.resolveFile.
        File resolved = SourceCatalog.resolveFile(null);
        assertEquals("sources-custom.yml", resolved.getName());
    }

    @Test
    void newestInstalledSourceIsTheOnlyPinnedSource(@TempDir Path tempDir) {
        File file = tempDir.resolve("sources-custom.yml").toFile();
        SourceCatalog.CatalogSource github = SourceCatalog.pinnedInstallation(
                "github", "owner/plugin", "https://github.com/owner/plugin", "1.0",
                "AA11", 100L, "Plugin-1.0.jar"
        );
        SourceCatalog.CatalogSource modrinth = SourceCatalog.pinnedInstallation(
                "modrinth", "plugin-id", "https://modrinth.com/plugin/plugin-id", "2.0",
                "BB22", 200L, "Plugin-2.0.jar"
        );

        assertTrue(SourceCatalog.writeUserEntry(file, "Plugin", "com.example.Plugin", github));
        assertTrue(SourceCatalog.writeUserEntry(file, "Plugin", "com.example.Plugin", modrinth));

        SourceCatalog catalog = new SourceCatalog(file);
        SourceCatalog.CatalogSource pinned = catalog.pinnedSource("com.example.Plugin", "Plugin");
        assertNotNull(pinned);
        assertEquals("modrinth", pinned.sourceId());
        assertEquals("plugin-id", pinned.ref());
        assertEquals("2.0", pinned.options().get("artifactVersion"));
        assertEquals("bb22", pinned.options().get("artifactSha256"));
        assertEquals("200", pinned.options().get("artifactSize"));
        assertEquals(1, catalog.lookup("com.example.Plugin", "Plugin").stream()
                .filter(source -> Boolean.parseBoolean(source.options().get("pinned")))
                .count());
    }

    @Test
    void pendingInstallationPromotesOnlyVerifiedActiveJar(@TempDir Path tempDir) throws Exception {
        File catalogFile = tempDir.resolve("sources-custom.yml").toFile();
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("Plugin-2.0.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write("name: Plugin\nversion: 2.0\nmain: com.example.Plugin\n".getBytes());
            output.closeEntry();
        }

        SourceCatalog.CatalogSource pending = SourceCatalog.pendingInstallation(
                "modrinth", "plugin", "https://modrinth.com/plugin/plugin", "2.0", "", Files.size(jar), jar.getFileName().toString());
        assertTrue(SourceCatalog.writeUserEntry(catalogFile, "Plugin", "com.example.Plugin", pending));

        SourceCatalog.reconcilePendingInstallations(catalogFile, pluginsDir.toFile());

        SourceCatalog.CatalogSource installed = new SourceCatalog(catalogFile).pinnedSource("com.example.Plugin", "Plugin");
        assertNotNull(installed);
        assertEquals("2.0", installed.options().get("artifactVersion"));
        assertEquals("Plugin-2.0.jar", installed.options().get("artifactFile"));
        assertFalse(installed.options().containsKey("pendingFile"));
    }

    private static String readSafely(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
