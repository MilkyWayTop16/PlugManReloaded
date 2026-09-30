package ru.milkyway.plugmanreloaded.commands.sub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LoadCommandPluginNameTest {

    @Test
    @DisplayName("Verify PluginJarIndex resolves declared plugin name from versioned jar file")
    void testDescriptorNameResolutionFromVersionedJar(@TempDir Path tempDir) throws Exception {
        File jarFile = tempDir.resolve("EssentialsX-2.20.1.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jarFile))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            String yml = "name: Essentials\nversion: '2.20.1'\nmain: com.earth2me.essentials.Essentials\n";
            zos.write(yml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(jarFile);
        assertNotNull(desc);
        assertEquals("Essentials", desc.declaredName());

        String rawPluginName = jarFile.getName().replaceAll("(?i)\\.jar$", "");
        assertEquals("EssentialsX-2.20.1", rawPluginName);

        String resolvedPluginName = null;
        if (desc.declaredName() != null && !desc.declaredName().isBlank()) {
            resolvedPluginName = desc.declaredName();
        }
        if (resolvedPluginName == null) {
            resolvedPluginName = rawPluginName;
        }

        assertEquals("Essentials", resolvedPluginName);
        assertNotEquals(rawPluginName, resolvedPluginName);
    }

    @Test
    @DisplayName("Verify fallback to filename when descriptor is missing declared name")
    void testFallbackWhenDescriptorMissingName(@TempDir Path tempDir) throws Exception {
        File jarFile = tempDir.resolve("CustomLib-1.0.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jarFile))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            String yml = "version: '1.0'\n";
            zos.write(yml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        PluginJarIndex.JarDescriptor desc = PluginJarIndex.readDescriptor(jarFile);
        String rawPluginName = jarFile.getName().replaceAll("(?i)\\.jar$", "");
        String resolved = (desc != null && desc.declaredName() != null && !desc.declaredName().isBlank())
                ? desc.declaredName()
                : rawPluginName;

        assertEquals("CustomLib-1.0", resolved);
    }
}
