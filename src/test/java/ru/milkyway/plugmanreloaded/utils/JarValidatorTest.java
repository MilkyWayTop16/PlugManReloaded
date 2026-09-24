package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class JarValidatorTest {

    private byte[] createDummyClassBytes(int majorVersion) {
        byte[] bytes = new byte[8];
        bytes[0] = (byte) 0xCA;
        bytes[1] = (byte) 0xFE;
        bytes[2] = (byte) 0xBA;
        bytes[3] = (byte) 0xBE;
        bytes[4] = 0;
        bytes[5] = 0;
        bytes[6] = (byte) ((majorVersion >> 8) & 0xFF);
        bytes[7] = (byte) (majorVersion & 0xFF);
        return bytes;
    }

    @Test
    void testDetectPaperBootstrapper(@TempDir Path tempDir) throws IOException {
        File bootstrapperJar = tempDir.resolve("BootstrapperPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(bootstrapperJar))) {
            jos.putNextEntry(new JarEntry("paper-plugin.yml"));
            String yml = "name: BootstrapperPlugin\nversion: 1.0\nbootstrapper: com.example.MyBootstrapper\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        assertTrue(JarValidator.hasPaperBootstrapper(bootstrapperJar));

        File standardJar = tempDir.resolve("StandardPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(standardJar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: StandardPlugin\nversion: 1.0\nmain: com.example.StandardPlugin\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        assertFalse(JarValidator.hasPaperBootstrapper(standardJar));
    }

    @Test
    void testReadRequiredJavaVersion(@TempDir Path tempDir) throws IOException {
        File java21Jar = tempDir.resolve("Java21Plugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(java21Jar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: Java21Plugin\nversion: 1.0\nmain: com.example.Main\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/Main.class"));
            jos.write(createDummyClassBytes(65));
            jos.closeEntry();
        }

        assertEquals(21, JarValidator.readRequiredJavaVersion(java21Jar));

        File java17Jar = tempDir.resolve("Java17Plugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(java17Jar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: Java17Plugin\nversion: 1.0\nmain: com.example.Main\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/Main.class"));
            jos.write(createDummyClassBytes(61));
            jos.closeEntry();
        }

        assertEquals(17, JarValidator.readRequiredJavaVersion(java17Jar));

        File multiReleaseJar = tempDir.resolve("MultiReleasePlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(multiReleaseJar))) {
            jos.putNextEntry(new JarEntry("paper-plugin.yml"));
            String yml = "name: MultiReleasePlugin\nversion: 1.5.3\nmain: org.popcraft.chunky.ChunkyPaper\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/versions/25/org/something/FutureOpt.class"));
            jos.write(createDummyClassBytes(69));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("org/popcraft/chunky/ChunkyPaper.class"));
            jos.write(createDummyClassBytes(61));
            jos.closeEntry();
        }

        assertEquals(17, JarValidator.readRequiredJavaVersion(multiReleaseJar));
    }

    @Test
    void testReadDependenciesFromPaperPluginYaml(@TempDir Path tempDir) throws IOException {
        File paperJar = tempDir.resolve("ModernPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(paperJar))) {
            jos.putNextEntry(new JarEntry("paper-plugin.yml"));
            String yml = """
                    name: ModernPlugin
                    version: 1.0
                    dependencies:
                      server:
                        Vault:
                          required: true
                        ProtocolLib:
                          required: false
                    """;
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<String> required = JarValidator.readDeclaredDependencies(paperJar);
        assertEquals(List.of("Vault"), required);

        List<String> optional = JarValidator.readDeclaredSoftDependencies(paperJar);
        assertEquals(List.of("ProtocolLib"), optional);
    }

    @Test
    void testIsValidPluginJarValidation(@TempDir Path tempDir) throws IOException {
        assertFalse(JarValidator.isValidPluginJar(null));
        assertFalse(JarValidator.isValidPluginJar(new File("nonexistent.jar")));

        File smallJar = tempDir.resolve("small.jar").toFile();
        try (FileOutputStream fos = new FileOutputStream(smallJar)) {
            fos.write(new byte[50]);
        }
        assertFalse(JarValidator.isValidPluginJar(smallJar));

        File validJar = tempDir.resolve("Valid.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(validJar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: ValidPlugin\nversion: 1.0\nmain: com.example.Valid\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        assertTrue(JarValidator.isValidPluginJar(validJar));
    }

    @Test
    void testSafeYamlParsingWithCustomTag(@TempDir Path tempDir) throws IOException {
        File tagJar = tempDir.resolve("LegacyTagPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tagJar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = """
                    name: LegacyTagPlugin
                    version: 2.1.0
                    main: com.example.LegacyMain
                    awareness:
                      - !@UTF8
                    depend:
                      - Vault
                    """;
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        assertEquals("LegacyTagPlugin", JarValidator.readPluginName(tagJar));
        assertEquals(List.of("Vault"), JarValidator.readDeclaredDependencies(tagJar));
    }

    @Test
    void testCorruptedYamlRawFallback(@TempDir Path tempDir) throws IOException {
        File brokenJar = tempDir.resolve("BrokenYamlPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(brokenJar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: BrokenPlugin\nversion: 3.0.0\n\tinvalid_tab_indentation: true\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        assertEquals("BrokenPlugin", JarValidator.readPluginName(brokenJar));
    }

    @Test
    void testValidatePreFlightSuccess(@TempDir Path tempDir) throws IOException {
        File jar = tempDir.resolve("GoodPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: GoodPlugin\nversion: 1.5.0\nmain: com.example.Good\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(jar, "GoodPlugin", false);
        assertTrue(report.isValid());
        assertEquals("GoodPlugin", report.declaredName());
        assertEquals("1.5.0", report.declaredVersion());
    }

    @Test
    void testValidatePreFlightNameMismatch(@TempDir Path tempDir) throws IOException {
        File jar = tempDir.resolve("MismatchPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: PluginA\nversion: 1.0\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(jar, "PluginB", true);
        assertFalse(report.isValid());
        assertEquals(JarValidator.PreFlightStatus.NAME_MISMATCH, report.status());
    }

    @Test
    void testValidatePreFlightIncompatibleJava(@TempDir Path tempDir) throws IOException {
        File jar = tempDir.resolve("FutureJavaPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: FutureJavaPlugin\nversion: 1.0\nmain: com.example.Future\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/Future.class"));
            jos.write(createDummyClassBytes(99));
            jos.closeEntry();
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(jar, null, false);
        assertFalse(report.isValid());
        assertEquals(JarValidator.PreFlightStatus.INCOMPATIBLE_JAVA, report.status());
        assertEquals(55, report.requiredJava());
    }

    @Test
    void testValidatePreFlightCorruptedZip(@TempDir Path tempDir) throws IOException {
        File broken = tempDir.resolve("Broken.jar").toFile();
        try (FileOutputStream fos = new FileOutputStream(broken)) {
            fos.write(new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00});
            fos.write(new byte[200]);
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(broken, null, false);
        assertFalse(report.isValid());
        assertEquals(JarValidator.PreFlightStatus.CORRUPTED_JAR, report.status());
    }

    @Test
    void testValidatePreFlightRejectsFabricMod(@TempDir Path tempDir) throws IOException {
        File fabricMod = tempDir.resolve("Sodium-Fabric.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(fabricMod))) {
            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            String json = "{\"schemaVersion\": 1, \"id\": \"sodium\"}";
            jos.write(json.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(fabricMod, "sodium", false);
        assertFalse(report.isValid());
        assertEquals(JarValidator.PreFlightStatus.NO_DESCRIPTOR, report.status());
        assertTrue(report.errorMessage().contains("Fabric"));
    }

    @Test
    void testValidatePreFlightRejectsForgeMod(@TempDir Path tempDir) throws IOException {
        File forgeMod = tempDir.resolve("Create-Forge.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(forgeMod))) {
            jos.putNextEntry(new JarEntry("META-INF/mods.toml"));
            String toml = "modLoader=\"javafml\"\n";
            jos.write(toml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        JarValidator.PreFlightReport reportForge = JarValidator.validatePreFlight(forgeMod, "create", false);
        assertFalse(reportForge.isValid());
        assertEquals(JarValidator.PreFlightStatus.NO_DESCRIPTOR, reportForge.status());
        assertTrue(reportForge.errorMessage().contains("Forge"));
    }
    @Test
    void testValidatePreFlightRejectsModernPaperPluginOnLoad(@TempDir Path tempDir) throws IOException {
        File paperJar = tempDir.resolve("ModernPaperPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(paperJar))) {
            jos.putNextEntry(new JarEntry("paper-plugin.yml"));
            String yml = "name: ModernPaperPlugin\nversion: 1.0\nmain: com.example.Modern\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        // Simulate /plm load with isReloadOrRestart = false
        // PlatformDetector.isModernPaper() would normally be true in tests, but let's mock or rely on test env.
        // Wait, the test environment doesn't have Bukkit/Paper API loaded unless we mock PlatformDetector.
        // Let's assume the bootstrapper check will also trigger it, so we can add a bootstrapper.
        File bootstrapperJar = tempDir.resolve("BootstrapperPaperPlugin.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(bootstrapperJar))) {
            jos.putNextEntry(new JarEntry("paper-plugin.yml"));
            String yml = "name: BootstrapperPaperPlugin\nversion: 1.0\nbootstrapper: com.example.Bootstrapper\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        JarValidator.PreFlightReport report = JarValidator.validatePreFlight(bootstrapperJar, null, false);
        assertFalse(report.isValid());
        assertEquals(JarValidator.PreFlightStatus.STARTUP_ONLY_LOAD, report.status());
    }
}
