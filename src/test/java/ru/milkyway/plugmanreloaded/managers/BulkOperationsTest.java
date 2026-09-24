package ru.milkyway.plugmanreloaded.managers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BulkOperationsTest {

    @Test
    @DisplayName("Test BulkOperationResult properties")
    void testBulkOperationResult() {
        BulkOperationResult empty = BulkOperationResult.empty();
        assertTrue(empty.isEmpty());
        assertFalse(empty.isAllSuccessful());
        assertEquals(0, empty.total());

        BulkOperationResult success = new BulkOperationResult(3, 3, List.of("A", "B", "C"), List.of(), Map.of(), 100L);
        assertTrue(success.isAllSuccessful());
        assertEquals(3, success.successCount());
        assertEquals(0, success.failedCount());

        BulkOperationResult partial = new BulkOperationResult(3, 2, List.of("A", "B"), List.of("C"), Map.of("C", "Error"), 150L);
        assertFalse(partial.isAllSuccessful());
        assertEquals(2, partial.successCount());
        assertEquals(1, partial.failedCount());
        assertEquals("Error", partial.failureReasons().get("C"));
    }

    @Test
    @DisplayName("Test topological sort for unloaded JAR files with dependencies")
    void testSortUnloadedJarsTopologically(@TempDir Path tempDir) throws IOException {
        File jarA = createFakePluginJar(tempDir.resolve("CorePlugin.jar").toFile(), "CorePlugin", "1.0", List.of(), List.of());
        File jarB = createFakePluginJar(tempDir.resolve("AddonPlugin.jar").toFile(), "AddonPlugin", "1.0", List.of("CorePlugin"), List.of());
        File jarC = createFakePluginJar(tempDir.resolve("SuperAddon.jar").toFile(), "SuperAddon", "1.0", List.of("AddonPlugin"), List.of());

        PluginJarIndex.JarInfo infoA = new PluginJarIndex.JarInfo(jarA, "CorePlugin", "1.0", "Author");
        PluginJarIndex.JarInfo infoB = new PluginJarIndex.JarInfo(jarB, "AddonPlugin", "1.0", "Author");
        PluginJarIndex.JarInfo infoC = new PluginJarIndex.JarInfo(jarC, "SuperAddon", "1.0", "Author");

        DependencyManager graphManager = new DependencyManager(null);

        List<PluginJarIndex.JarInfo> input = List.of(infoC, infoB, infoA);
        List<PluginJarIndex.JarInfo> sorted = graphManager.sortUnloadedJarsTopologically(input);

        assertEquals(3, sorted.size());
        int indexA = -1;
        int indexB = -1;
        int indexC = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).declaredName().equals("CorePlugin")) indexA = i;
            if (sorted.get(i).declaredName().equals("AddonPlugin")) indexB = i;
            if (sorted.get(i).declaredName().equals("SuperAddon")) indexC = i;
        }

        assertTrue(indexA < indexB, "CorePlugin must precede AddonPlugin");
        assertTrue(indexB < indexC, "AddonPlugin must precede SuperAddon");
    }

    @Test
    @DisplayName("Test topological sort for unloaded JAR files with provides")
    void testSortUnloadedJarsWithProvides(@TempDir Path tempDir) throws IOException {
        File jarVault = createFakePluginJar(tempDir.resolve("VaultImpl.jar").toFile(), "VaultImpl", "1.0", List.of(), List.of("Vault"));
        File jarShop = createFakePluginJar(tempDir.resolve("ShopPlugin.jar").toFile(), "ShopPlugin", "1.0", List.of("Vault"), List.of());

        PluginJarIndex.JarInfo infoVault = new PluginJarIndex.JarInfo(jarVault, "VaultImpl", "1.0", "Author");
        PluginJarIndex.JarInfo infoShop = new PluginJarIndex.JarInfo(jarShop, "ShopPlugin", "1.0", "Author");

        DependencyManager graphManager = new DependencyManager(null);

        List<PluginJarIndex.JarInfo> input = List.of(infoShop, infoVault);
        List<PluginJarIndex.JarInfo> sorted = graphManager.sortUnloadedJarsTopologically(input);

        assertEquals(2, sorted.size());
        assertEquals("VaultImpl", sorted.get(0).declaredName());
        assertEquals("ShopPlugin", sorted.get(1).declaredName());
    }

    private File createFakePluginJar(File file, String name, String version, List<String> depend, List<String> provides) throws IOException {
        StringBuilder yml = new StringBuilder();
        yml.append("name: ").append(name).append("\n");
        yml.append("version: ").append(version).append("\n");
        yml.append("main: ru.test.").append(name).append("\n");
        if (!depend.isEmpty()) {
            yml.append("depend: [").append(String.join(", ", depend)).append("]\n");
        }
        if (!provides.isEmpty()) {
            yml.append("provides: [").append(String.join(", ", provides)).append("]\n");
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            ZipEntry entry = new ZipEntry("plugin.yml");
            zos.putNextEntry(entry);
            zos.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return file;
    }
}
