package ru.milkyway.plugmanreloaded.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ru.milkyway.plugmanreloaded.update.PluginMatcher;
import ru.milkyway.plugmanreloaded.update.ServerProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class PluginDownloaderTest {

    @Test
    @DisplayName("Verify GitHub release selection checks draft, prerelease, and companion artifacts")
    void resolveGithubDownloadAppliesTheSameSafetyChecksAsGithubSource() throws Exception {
        ClassReader reader = new ClassReader(DownloadResolver.class.getName());
        List<String> ldcStrings = new ArrayList<>();
        List<String> invokedMethods = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!"resolveGithubDownload".equals(name) && !"latestGithubRelease".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String s) {
                            ldcStrings.add(s);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDescriptor, boolean isInterface) {
                        invokedMethods.add(owner + "." + mName);
                    }
                };
            }
        }, 0);

        assertTrue(ldcStrings.contains("draft"), "resolveGithubDownload must check 'draft' field");
        assertTrue(ldcStrings.contains("prerelease"), "resolveGithubDownload must check 'prerelease' field");
        assertTrue(invokedMethods.stream().anyMatch(m -> m.contains("PluginMatcher") && m.contains("isCompanion")),
                "resolveGithubDownload must filter companion artifacts using PluginMatcher.isCompanion");
    }

    @Test
    @DisplayName("Verify AssetUtil correctly identifies companion and non-runtime jars")
    void testAssetUtilCompanionFiltering() {
        assertTrue(PluginMatcher.isCompanion("CoreProtect", "CoreProtect-api-21.2.jar"));
        assertTrue(PluginMatcher.isCompanion("Essentials", "EssentialsX-chat-2.20.0.jar"));
        assertFalse(PluginMatcher.isCompanion("CoreProtect", "CoreProtect-21.2.jar"));

        assertTrue(PluginMatcher.isNonRuntimeArtifact("CoreProtect-21.2-sources.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("CoreProtect-21.2-javadoc.jar"));
        assertFalse(PluginMatcher.isNonRuntimeArtifact("CoreProtect-21.2.jar"));
    }

    @Test
    void downloadedDescriptorMustBelongToSelectedProject() {
        DownloadModels.SearchResultEntry selected = entry("coins-engine", "CoinsEngine", "author", null);
        assertTrue(PluginDownloader.artifactMatchesExpectedProject(selected, "CoinsEngine"));
        assertFalse(PluginDownloader.artifactMatchesExpectedProject(selected, "OrdeructionSystem"));
        assertFalse(PluginDownloader.artifactMatchesExpectedProject(entry("worldedit", "WorldEdit", "author", null), "FastAsyncWorldEdit"));
        assertFalse(PluginDownloader.artifactMatchesExpectedProject(entry("discord-chat", "Discord Chat", "author", null), "DiscordSRV"));

        DownloadModels.SearchResultEntry direct = entry("owner/custom-project", "owner/custom-project", "Unknown", null);
        assertFalse(PluginDownloader.artifactMatchesExpectedProject(direct, "InternalPluginName"));

        DownloadModels.SearchResultEntry numericSpigot = new DownloadModels.SearchResultEntry(
                "spigot", "105658", "105658", "author", "1.0", "", "https://spigotmc.org/resources/105658", null,
                0, 0, 0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
        assertTrue(PluginDownloader.artifactMatchesExpectedProject(numericSpigot, "ajLeaderboards"));

        DownloadModels.SearchResultEntry resourceNamed = new DownloadModels.SearchResultEntry(
                "spigot", "105658", "Resource 105658", "author", "1.0", "", "https://spigotmc.org/resources/105658", null,
                0, 0, 0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
        assertTrue(PluginDownloader.artifactMatchesExpectedProject(resourceNamed, "ajLeaderboards"));

        PluginSearch.rememberTitleGlobally("99999", "spigot", "DecentHolograms");
        DownloadModels.SearchResultEntry withKnown = new DownloadModels.SearchResultEntry(
                "spigot", "99999", "RandomTitle", "author", "1.0", "", "https://spigotmc.org/resources/99999", null,
                0, 0, 0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
        assertTrue(PluginDownloader.artifactMatchesExpectedProject(withKnown, "DecentHolograms"));
    }

    @Test
    void modrinthVersionMustMatchServerAndLoader() {
        JsonObject version = new JsonObject();
        JsonArray gameVersions = new JsonArray();
        gameVersions.add("1.20.4");
        version.add("game_versions", gameVersions);
        JsonArray loaders = new JsonArray();
        loaders.add("paper");
        version.add("loaders", loaders);

        assertTrue(DownloadResolver.isModrinthVersionCompatible(version, "1.20.4", Set.of("paper")));
        assertFalse(DownloadResolver.isModrinthVersionCompatible(version, "1.21.1", Set.of("paper")));
        assertFalse(DownloadResolver.isModrinthVersionCompatible(version, "1.20.4", Set.of("velocity")));
    }

    @Test
    void modrinthSelectsPrimaryRuntimeJar() {
        JsonArray files = new JsonArray();
        files.add(file("Plugin-sources.jar", true));
        files.add(file("Plugin-all.jar", false));
        files.add(file("Plugin-paper.jar", true));

        JsonObject selected = DownloadResolver.selectModrinthRuntimeFile(files);
        assertNotNull(selected);
        assertEquals("Plugin-paper.jar", selected.get("filename").getAsString());
    }

    @Test
    void hangarRequiresPaperArtifactAndCompatibleVersion() {
        JsonObject version = new JsonObject();
        JsonObject downloads = new JsonObject();
        downloads.add("PAPER", new JsonObject());
        version.add("downloads", downloads);
        JsonObject dependencies = new JsonObject();
        JsonArray paper = new JsonArray();
        paper.add("1.20 - 1.20.6");
        dependencies.add("PAPER", paper);
        version.add("platformDependencies", dependencies);

        assertTrue(DownloadResolver.isHangarVersionCompatible(version, "1.20.4"));
        version.remove("downloads");
        assertFalse(DownloadResolver.isHangarVersionCompatible(version, "1.20.4"));
    }

    @Test
    void installationPlanRejectsArtifactChangedAfterValidation(@TempDir Path tempDir) throws Exception {
        byte[] original = "original artifact".getBytes(StandardCharsets.UTF_8);
        Path artifact = tempDir.resolve("Plugin.jar.tmp");
        Files.write(artifact, original);
        String sha256 = PluginMatcher.toHex(MessageDigest.getInstance("SHA-256").digest(original));
        PluginDownloader.StagedItem item = new PluginDownloader.StagedItem(
                artifact, "Plugin", "1.0", "modrinth", "plugin", null, false,
                entry("plugin", "Plugin", "author", null), sha256, original.length
        );

        assertNull(PluginDownloader.validateStagedItems(tempDir, List.of(item)));
        Files.writeString(artifact, "changed artifact");
        PluginDownloader.StageFailure failure = PluginDownloader.validateStagedItems(tempDir, List.of(item));

        assertNotNull(failure);
        assertEquals(DownloadModels.DownloadStatus.HASH_MISMATCH, failure.outcome());
    }

    @Test
    void spigotResolutionPreservesEntryVersion() {
        DownloadResolver resolver = new DownloadResolver(null, ServerProfile.detect());
        DownloadModels.SearchResultEntry entry = new DownloadModels.SearchResultEntry(
                "spigot", "12345", "ajLeaderboards", "ajgeiss0702", "2.11.0", "", "https://spigotmc.org/resources/12345", "https://api.spiget.org/v2/resources/12345/download",
                0, 0, 0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
        DownloadResolver.DownloadResolution resolution = resolver.resolve(entry);
        assertNotNull(resolution.info());
        assertEquals("2.11.0", resolution.info().versionNumber());
        assertEquals("ajLeaderboards.jar", resolution.info().fileName());
    }

    private static JsonObject file(String name, boolean primary) {
        JsonObject file = new JsonObject();
        file.addProperty("filename", name);
        file.addProperty("primary", primary);
        return file;
    }

    private static DownloadModels.SearchResultEntry entry(String id, String title, String author, String downloadUrl) {
        return new DownloadModels.SearchResultEntry(
                "modrinth", id, title, author, "1.0", "", "https://example.com", downloadUrl,
                0, 0, 0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                null, null, null, false, true
        );
    }
}
