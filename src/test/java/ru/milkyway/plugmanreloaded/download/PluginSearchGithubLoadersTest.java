package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginSearchGithubLoadersTest {

    @Test
    @DisplayName("Verify searchGithub never references paper or spigot constant in bytecode")
    void searchGithubNeverFakesPaperOrSpigotAsLoaders() throws Exception {
        ClassReader reader = new ClassReader(PluginSearch.class.getName());
        List<String> ldcStrings = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!"searchGithub".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String s) {
                            ldcStrings.add(s);
                        }
                    }
                };
            }
        }, 0);

        assertFalse(ldcStrings.contains("paper"), "searchGithub must not hardcode 'paper' as loader");
        assertFalse(ldcStrings.contains("spigot"), "searchGithub must not hardcode 'spigot' as loader");
    }

    @Test
    @DisplayName("Verify GitHub results without plugin topics receive noise penalty while genuine plugin topics receive platform boost")
    void verifyGithubResultsWithoutPluginTopicsArePenalized() {
        PluginSearch search = new PluginSearch(null, null, null, null);

        SearchResultEntry genericRepo = new SearchResultEntry(
                "github", "user/demo", "demo-app", "user", "1.0", "some generic repository",
                "https://github.com/user/demo", null, 0L, 10, 0.0,
                List.of(), List.of("utilities", "react"), List.of(), null, null, null, false, true
        );

        SearchResultEntry pluginRepo = new SearchResultEntry(
                "github", "user/demo", "demo-app", "user", "1.0", "demo plugin",
                "https://github.com/user/demo", null, 0L, 10, 0.0,
                List.of(), List.of("spigot", "paper"), List.of(), null, null, null, false, true
        );

        double genericScore = search.computeRelevanceScore("query", genericRepo);
        double pluginScore = search.computeRelevanceScore("query", pluginRepo);

        assertTrue(pluginScore > genericScore, "Plugin repo score must exceed non-plugin repo score");
        assertTrue(genericScore < 10.0, "Non-plugin GitHub repo must receive heavy noise penalty");
    }
}
