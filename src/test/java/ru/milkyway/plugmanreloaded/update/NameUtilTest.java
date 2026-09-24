package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameUtilTest {

    @Test
    @DisplayName("normalizeName strips version patterns, spaces, separators, and platform suffixes")
    void normalizeNameTests() {
        assertEquals("vault", PluginMatcher.normalizeName("Vault"));
        assertEquals("vault", PluginMatcher.normalizeName("Vault-1.7.3"));
        assertEquals("vault", PluginMatcher.normalizeName("Vault v1.7.3"));
        assertEquals("essentialsx", PluginMatcher.normalizeName("EssentialsX-2.20.1"));
        assertEquals("essentialsx", PluginMatcher.normalizeName("EssentialsX-spigot"));
        assertEquals("decentholograms", PluginMatcher.normalizeName("DecentHolograms-2.8.11"));
        assertEquals("decentholograms", PluginMatcher.normalizeName("DecentHolograms-paper"));
        assertEquals("", PluginMatcher.normalizeName(null));
        assertEquals("", PluginMatcher.normalizeName(""));
    }

    @Test
    @DisplayName("isCompanion identifies companion plugins and avoids false positives on genuine base plugins")
    void isCompanionTests() {
        assertTrue(PluginMatcher.isCompanion("Vault", "VaultAPI"));
        assertTrue(PluginMatcher.isCompanion("Essentials", "EssentialsChat"));
        assertTrue(PluginMatcher.isCompanion("EssentialsX", "EssentialsXSpawn"));
        assertTrue(PluginMatcher.isCompanion("WorldGuard", "WorldGuard-Config"));

        assertFalse(PluginMatcher.isCompanion("Vault", "Vault"));
        assertFalse(PluginMatcher.isCompanion("LuckPerms", "LuckPerms"));
        assertFalse(PluginMatcher.isCompanion("DecentHolograms", "HolographicDisplays"));
    }

    @Test
    @DisplayName("getSearchAliases provides common aliases and camel-case splits")
    void getSearchAliasesTests() {
        List<String> faweAliases = PluginMatcher.getSearchAliases("fawe");
        assertTrue(faweAliases.contains("fastasyncworldedit"));
        assertTrue(PluginMatcher.getSearchAliases("fastasyncworldedit").contains("fawe"));

        List<String> essAliases = PluginMatcher.getSearchAliases("essentials");
        assertTrue(essAliases.contains("essentialsx"));
        assertTrue(PluginMatcher.getSearchAliases("essentialsx").contains("essentials"));
        assertFalse(PluginMatcher.getSearchAliases("essentialspay").contains("essentialsx"));
        assertFalse(PluginMatcher.getSearchAliases("essentialsxspawn").contains("essentials"));

        List<String> citizensAliases = PluginMatcher.getSearchAliases("citizens");
        assertTrue(citizensAliases.contains("citizens2"));
        assertTrue(PluginMatcher.getSearchAliases("citizens2").contains("citizens"));
    }

    @Test
    @DisplayName("similarity measures matching ratio between strings")
    void similarityTests() {
        assertEquals(1.0, PluginMatcher.similarity("vault", "vault"));
        assertTrue(PluginMatcher.similarity("vault", "vaultapi") > 0.6);
        assertTrue(PluginMatcher.similarity("completely", "different") < 0.3);
    }

    @Test
    @DisplayName("isExactOrCleanMatch matches exact or cleaned names but rejects near-misses")
    void exactOrCleanMatchTests() {
        assertFalse(PluginMatcher.isExactOrCleanMatch("LiteCheck", "EliteCheck"));
        assertFalse(PluginMatcher.isExactOrCleanMatch("LiteCheck", "EliteCheck", "elitecheck"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("LiteCheck", "LiteCheck"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("LiteCheck", "[1.16-1.21] LiteCheck v1.0"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("BedrockMovementFix", "Bedrock Movement Fix - DISCONTINUED"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("BedrockMovementFix", "BedrockMovementFix"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("PlaceholderAPI", "PlaceholderAPI [1.16-1.20]"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("LiteCheck", null, "litecheck"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("FAWE", "FastAsyncWorldEdit"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("FastAsyncWorldEdit", "FAWE"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("Citizens", "Citizens2"));
        assertTrue(PluginMatcher.isExactOrCleanMatch("Citizens2", "Citizens"));
    }

    @Test
    @DisplayName("resourceNameSimilarity handles exact matches and aliases with maximum score")
    void resourceNameSimilarityTests() {
        assertEquals(1.0, PluginMatcher.resourceNameSimilarity("FAWE", "FastAsyncWorldEdit"));
        assertEquals(1.0, PluginMatcher.resourceNameSimilarity("FastAsyncWorldEdit", "FAWE"));
        assertEquals(1.0, PluginMatcher.resourceNameSimilarity("Citizens", "Citizens2"));
        assertEquals(1.0, PluginMatcher.resourceNameSimilarity("Citizens2", "Citizens"));
        assertEquals(1.0, PluginMatcher.resourceNameSimilarity("PlaceholderAPI", "PlaceholderAPI [1.16-1.20]"));
        assertEquals(0.0, PluginMatcher.resourceNameSimilarity(null, "Vault"));
        assertEquals(0.0, PluginMatcher.resourceNameSimilarity("Vault", ""));
    }
}
