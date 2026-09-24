package ru.milkyway.plugmanreloaded.update.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ru.milkyway.plugmanreloaded.update.UpdateModels.*;
import ru.milkyway.plugmanreloaded.update.UpdateCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GithubSourceTest {

    @Test
    @DisplayName("Verify identifyByName never calls search APIs or references search endpoints in bytecode")
    void identifyByNameNeverCallsGithubSearchApi() throws Exception {
        ClassReader reader = new ClassReader(GithubSource.class.getName());
        List<String> stringConstants = new ArrayList<>();
        List<String> invokedMethods = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!"identifyByName".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String str) {
                            stringConstants.add(str);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        invokedMethods.add(owner + "." + name);
                    }
                };
            }
        }, 0);

        for (String c : stringConstants) {
            assertFalse(c.contains("search/repositories"), "identifyByName must never reference /search/repositories");
        }
        for (String m : invokedMethods) {
            assertFalse(m.toLowerCase().contains("search"), "identifyByName must never invoke search methods: " + m);
        }
    }

    @Test
    @DisplayName("Verify identifyByName without authors returns null and caches miss without network")
    void testIdentifyByNameWithoutAuthorsCachesMiss() {
        UpdateCache cache = new UpdateCache(3600_000L);
        GithubSource source = new GithubSource(cache, null);
        PluginIdentity identity = new PluginIdentity("SamplePlugin", "com.example.Sample", "1.0", List.of(), null, null, null, null);

        UpdateSource.ProjectMatch match = source.identifyByName(identity);
        assertNull(match);
        assertEquals(Boolean.TRUE, cache.get("github:name:sampleplugin:miss", Boolean.class));

        UpdateSource.ProjectMatch secondAttempt = source.identifyByName(identity);
        assertNull(secondAttempt);
    }

    @Test
    @DisplayName("Verify identifyByName returns cached match directly")
    void testIdentifyByNameCachedHit() {
        UpdateCache cache = new UpdateCache(3600_000L);
        GithubSource source = new GithubSource(cache, null);
        PluginIdentity identity = new PluginIdentity("CachedPlugin", "com.example.Cached", "1.0", List.of(), null, null, null, null);

        UpdateSource.ProjectMatch cached = new UpdateSource.ProjectMatch(
                "CachedPlugin", "owner/repo", "https://github.com/owner/repo",
                MatchConfidence.CONFIRMED, MatchReason.CATALOG, null
        );
        cache.put("github:name:cachedplugin", cached);

        UpdateSource.ProjectMatch result = source.identifyByName(identity);
        assertSame(cached, result);
    }

    @Test
    @DisplayName("Verify identifyFromCatalog builds confirmed match from valid ref")
    void testIdentifyFromCatalog() {
        UpdateCache cache = new UpdateCache(3600_000L);
        GithubSource source = new GithubSource(cache, null);
        PluginIdentity identity = new PluginIdentity("TestPlugin", "com.example.Test", "1.0", List.of(), null, null, null, null);

        assertNull(source.identifyFromCatalog(identity, null, Map.of()));
        assertNull(source.identifyFromCatalog(identity, "invalid-ref-without-slash", Map.of()));

        UpdateSource.ProjectMatch match = source.identifyFromCatalog(identity, "user/repo", Map.of());
        assertNotNull(match);
        assertEquals("user/repo", match.projectRef());
        assertEquals("https://github.com/user/repo", match.projectUrl());
        assertEquals(MatchConfidence.CONFIRMED, match.confidence());
        assertEquals(MatchReason.CATALOG, match.reason());
    }
}
