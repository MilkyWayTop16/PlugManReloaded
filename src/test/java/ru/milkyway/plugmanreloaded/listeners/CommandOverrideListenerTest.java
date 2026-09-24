package ru.milkyway.plugmanreloaded.listeners;

import ru.milkyway.plugmanreloaded.commands.CommandOverrideListener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandOverrideListenerTest {

    @Test
    @DisplayName("Test basic /pl and /plugins token extraction")
    void testBasicExtraction() {
        String[] tokens1 = CommandOverrideListener.extractPluginsCommandTokens("/pl");
        assertNotNull(tokens1);
        assertEquals(1, tokens1.length);
        assertEquals("pl", tokens1[0]);

        String[] tokens2 = CommandOverrideListener.extractPluginsCommandTokens("/plugins");
        assertNotNull(tokens2);
        assertEquals(1, tokens2.length);
        assertEquals("plugins", tokens2[0]);
    }

    @Test
    @DisplayName("Test flag and argument forwarding")
    void testFlagForwarding() {
        String[] tokens = CommandOverrideListener.extractPluginsCommandTokens("/pl -v -j");
        assertNotNull(tokens);
        assertEquals(3, tokens.length);
        assertEquals("pl", tokens[0]);
        assertEquals("-v", tokens[1]);
        assertEquals("-j", tokens[2]);
    }

    @Test
    @DisplayName("Test namespace commands matching")
    void testNamespaceMatching() {
        assertNotNull(CommandOverrideListener.extractPluginsCommandTokens("/bukkit:plugins"));
        assertNotNull(CommandOverrideListener.extractPluginsCommandTokens("/paper:pl -v"));
        assertNotNull(CommandOverrideListener.extractPluginsCommandTokens("/purpur:plugins"));
        assertNotNull(CommandOverrideListener.extractPluginsCommandTokens("/spigot:pl"));
        assertNotNull(CommandOverrideListener.extractPluginsCommandTokens("/minecraft:plugins"));

        assertNull(CommandOverrideListener.extractPluginsCommandTokens("/essentials:help"));
        assertNull(CommandOverrideListener.extractPluginsCommandTokens("/plm list"));
    }

    @Test
    @DisplayName("Test multiple slashes and spacing")
    void testMultipleSlashesAndSpacing() {
        String[] tokens = CommandOverrideListener.extractPluginsCommandTokens("///pl    -v");
        assertNotNull(tokens);
        assertEquals(2, tokens.length);
        assertEquals("pl", tokens[0]);
        assertEquals("-v", tokens[1]);
    }

    @Test
    @DisplayName("Test non-matching commands return null")
    void testNonMatching() {
        assertNull(CommandOverrideListener.extractPluginsCommandTokens("/play"));
        assertNull(CommandOverrideListener.extractPluginsCommandTokens("/pluginmanager"));
        assertNull(CommandOverrideListener.extractPluginsCommandTokens(""));
        assertNull(CommandOverrideListener.extractPluginsCommandTokens(null));
    }
}
