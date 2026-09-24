package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarScannerTest {

    @TempDir
    Path tempDir;

    private byte[] createClassWithConstant(String constant) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(0xCAFEBABE);
        dos.writeShort(0);
        dos.writeShort(52);
        dos.writeShort(3);

        byte[] utfBytes = constant.getBytes(StandardCharsets.UTF_8);
        dos.writeByte(1);
        dos.writeShort(utfBytes.length);
        dos.write(utfBytes);

        dos.writeByte(7);
        dos.writeShort(1);

        dos.writeShort(0x0021);
        dos.writeShort(2);
        dos.writeShort(0);
        dos.writeShort(0);
        dos.writeShort(0);
        dos.writeShort(0);
        dos.writeShort(0);

        dos.flush();
        return baos.toByteArray();
    }

    @Test
    @DisplayName("Discover Spigot and GitHub URLs from class constant pool in normal jar")
    void discoverUrlsFromNormalJar() throws IOException {
        File jar = tempDir.resolve("normal.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("com/example/myplugin/Main.class"));
            zos.write(createClassWithConstant("https://github.com/test-owner/test-repo"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/myplugin/Updater.class"));
            zos.write(createClassWithConstant("https://www.spigotmc.org/resources/sample.99887/"));
            zos.closeEntry();
        }

        JarScanner scanner = new JarScanner();
        List<JarScanner.DiscoveredRef> refs = scanner.scan(jar, "com.example.myplugin.Main");

        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(r -> "github".equals(r.sourceId()) && "test-owner/test-repo".equals(r.ref())));
        assertTrue(refs.stream().anyMatch(r -> "spigot".equals(r.sourceId()) && "99887".equals(r.ref())));
    }

    @Test
    @DisplayName("Discover Modrinth URL from class constant pool")
    void discoverModrinthUrl() throws IOException {
        File jar = tempDir.resolve("modrinth.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("com/example/myplugin/Main.class"));
            zos.write(createClassWithConstant("https://modrinth.com/project/my-cool-plugin"));
            zos.closeEntry();
        }

        JarScanner scanner = new JarScanner();
        List<JarScanner.DiscoveredRef> refs = scanner.scan(jar, "com.example.myplugin.Main");

        assertEquals(1, refs.size());
        assertEquals("modrinth", refs.get(0).sourceId());
        assertEquals("my-cool-plugin", refs.get(0).ref());
    }

    @Test
    @DisplayName("Discover repository URL from bundled own pom.xml")
    void discoverFromBundledMavenPom() throws IOException {
        File jar = tempDir.resolve("with-pom.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("META-INF/maven/com.example/myplugin/pom.xml"));
            String pomXml = "<project><url>https://github.com/my-group/pom-project</url></project>";
            zos.write(pomXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        JarScanner scanner = new JarScanner();
        List<JarScanner.DiscoveredRef> refs = scanner.scan(jar, "com.example.myplugin.Main");

        assertEquals(1, refs.size());
        assertEquals("github", refs.get(0).sourceId());
        assertEquals("my-group/pom-project", refs.get(0).ref());
    }

    @Test
    @DisplayName("Shaded library packages are skipped to prevent false detections")
    void shadedLibrariesAreSkipped() throws IOException {
        File jar = tempDir.resolve("shaded.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("org/bstats/Metrics.class"));
            zos.write(createClassWithConstant("https://github.com/BStats/Metrics"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/google/common/Collect.class"));
            zos.write(createClassWithConstant("https://github.com/google/guava"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("net/kyori/adventure/Text.class"));
            zos.write(createClassWithConstant("https://github.com/KyoriPowered/adventure"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/myplugin/PluginMain.class"));
            zos.write(createClassWithConstant("https://github.com/actual-owner/actual-plugin"));
            zos.closeEntry();
        }

        JarScanner scanner = new JarScanner();
        List<JarScanner.DiscoveredRef> refs = scanner.scan(jar, "com.example.myplugin.PluginMain");

        assertEquals(1, refs.size());
        assertEquals("github", refs.get(0).sourceId());
        assertEquals("actual-owner/actual-plugin", refs.get(0).ref());
    }

    @Test
    @DisplayName("Jar with regular code and no URLs returns empty list")
    void jarWithoutUrlsReturnsEmptyList() throws IOException {
        File jar = tempDir.resolve("no-urls.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("com/example/myplugin/Main.class"));
            zos.write(createClassWithConstant("some.plain.identifier.without.links"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/myplugin/Util.class"));
            zos.write(createClassWithConstant("another_plain_string_constant"));
            zos.closeEntry();
        }

        JarScanner scanner = new JarScanner();
        List<JarScanner.DiscoveredRef> refs = scanner.scan(jar, "com.example.myplugin.Main");

        assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("Large entries exceeding size limits are safely ignored")
    void largeEntriesAreIgnored() throws IOException {
        File jar = tempDir.resolve("large.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("com/example/myplugin/BigBlob.class"));
            byte[] bigData = new byte[300_000];
            System.arraycopy(createClassWithConstant("https://github.com/huge/ignored"), 0, bigData, 0, 50);
            zos.write(bigData);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/myplugin/Normal.class"));
            zos.write(createClassWithConstant("https://github.com/normal-owner/normal-plugin"));
            zos.closeEntry();
        }

        JarScanner scanner = new JarScanner();
        List<JarScanner.DiscoveredRef> refs = scanner.scan(jar, "com.example.myplugin.Normal");

        assertEquals(1, refs.size());
        assertEquals("normal-owner/normal-plugin", refs.get(0).ref());
    }
}
