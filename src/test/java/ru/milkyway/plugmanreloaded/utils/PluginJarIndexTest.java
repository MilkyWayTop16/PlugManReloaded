package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class PluginJarIndexTest {

    @Test
    void testReadDescriptorFromPluginYml(@TempDir Path tempDir) throws IOException {
        File jar = tempDir.resolve("TestPlugin-1.0.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: TestPlugin\nversion: 1.2.3\nauthor: MilkyWay\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(jar);
        assertNotNull(desc);
        assertEquals("TestPlugin", desc.declaredName());
        assertEquals("1.2.3", desc.version());
        assertEquals("MilkyWay", desc.authors());
    }

    @Test
    void testReadDescriptorFromPaperPluginYml(@TempDir Path tempDir) throws IOException {
        File jar = tempDir.resolve("PaperTest-2.0.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("paper-plugin.yml"));
            String yml = "name: ModernPaperPlugin\nversion: 2.0.0\nauthors:\n  - Alice\n  - Bob\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(jar);
        assertNotNull(desc);
        assertEquals("ModernPaperPlugin", desc.declaredName());
        assertEquals("2.0.0", desc.version());
        assertEquals("Alice, Bob", desc.authors());
    }

    @Test
    void testReadDescriptorFromCorruptedOrEmptyJar(@TempDir Path tempDir) throws IOException {
        File emptyJar = tempDir.resolve("Empty.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(emptyJar))) {
            jos.putNextEntry(new JarEntry("README.txt"));
            jos.write("No plugin info".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(emptyJar);
        assertNull(desc);
    }

    @Test
    void testReadDescriptorWithCustomTagUtf8(@TempDir Path tempDir) throws IOException {
        File jar = tempDir.resolve("RefontCrafts-1.0.9.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = """
                    name: RefontCrafts
                    version: '1.0.9'
                    main: ru.refontstudio.refontcrafts.RefontCrafts
                    awareness:
                      - !@UTF8
                    softdepend:
                      - AdvancedEnchantments
                    """;
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(jar);
        assertNotNull(desc);
        assertEquals("RefontCrafts", desc.declaredName());
        assertEquals("1.0.9", desc.version());
    }
}
