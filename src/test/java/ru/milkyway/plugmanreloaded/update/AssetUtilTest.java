package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetUtilTest {

    @Test
    @DisplayName("isCompanion identifies companion jar filenames and permits base plugin jars")
    void isCompanionTests() {
        assertTrue(PluginMatcher.isCompanion("CoreProtect", "CoreProtect-api-21.2.jar"));
        assertTrue(PluginMatcher.isCompanion("Essentials", "EssentialsX-chat-2.20.0.jar"));
        assertTrue(PluginMatcher.isCompanion("EssentialsX", "EssentialsX-spawn-2.20.0.jar"));
        assertTrue(PluginMatcher.isCompanion("EssentialsX", "EssentialsX-discord-2.20.0.jar"));

        assertFalse(PluginMatcher.isCompanion("CoreProtect", "CoreProtect-21.2.jar"));
        assertFalse(PluginMatcher.isCompanion("EssentialsX", "EssentialsX-2.20.0.jar"));
        assertFalse(PluginMatcher.isCompanion("Vault", "Vault-1.7.3.jar"));
    }

    @Test
    @DisplayName("isNonRuntimeArtifact identifies non-runtime classifiers and foreign platforms")
    void isNonRuntimeArtifactTests() {
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-sources.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-javadoc.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-shaded-sources.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-tests.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-bungee.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-velocity.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-fabric.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-forge.jar"));
        assertTrue(PluginMatcher.isNonRuntimeArtifact(null));

        assertFalse(PluginMatcher.isNonRuntimeArtifact("plugin-1.0.jar"));
        assertFalse(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-paper.jar"));
        assertFalse(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-purpur.jar"));
        assertFalse(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-spigot.jar"));
        assertFalse(PluginMatcher.isNonRuntimeArtifact("plugin-1.0-folia.jar"));
    }

    @Test
    @DisplayName("platformBonus computes appropriate score based on server profile loaders")
    void platformBonusTests() {
        ServerProfile paperProfile = ServerProfile.of("1.20.4", Set.of("paper", "bukkit"), 17);
        assertTrue(PluginMatcher.platformBonus("Plugin-paper.jar", paperProfile) > PluginMatcher.platformBonus("Plugin-bukkit.jar", paperProfile));

        ServerProfile foliaProfile = ServerProfile.of("1.20.4", Set.of("folia", "paper", "bukkit"), 17);
        assertTrue(PluginMatcher.platformBonus("Plugin-folia.jar", foliaProfile) > PluginMatcher.platformBonus("Plugin-paper.jar", foliaProfile));
    }

    @Test
    @DisplayName("similarity measures string similarity between normalized plugin and asset names")
    void similarityTests() {
        assertEquals(1.0, PluginMatcher.similarity("Vault", "Vault-1.7.3.jar"));
        assertEquals(1.0, PluginMatcher.similarity("EssentialsX", "EssentialsX-2.20.0-paper.jar"));
        assertEquals(0.0, PluginMatcher.similarity("", ""));
    }
}
