package ru.milkyway.plugmanreloaded.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HexColorsTest {

    @Test
    @DisplayName("Test Bukkit legacy color translation")
    void testLegacyColors() {
        String translated = HexColors.translate("&aHello &cWorld &lBold");
        assertTrue(translated.contains("§aHello"));
        assertTrue(translated.contains("§cWorld"));
        assertTrue(translated.contains("§lBold"));
    }

    @Test
    @DisplayName("Test BungeeCord &#RRGGBB hex translation")
    void testBungeeHex() {
        String translated = HexColors.translate("&#ff5555Red &#55ff55Green");
        assertTrue(translated.contains("§x§f§f§5§5§5§5Red"));
        assertTrue(translated.contains("§x§5§5§f§f§5§5Green"));
    }

    @Test
    @DisplayName("Test Spigot &x&r&r&g&g&b&b hex translation")
    void testSpigotHex() {
        String translated = HexColors.translate("&x&f&f&a&a&0&0Gold");
        assertTrue(translated.contains("§x§f§f§a§a§0§0Gold"));
    }

    @Test
    @DisplayName("Test DecentHolograms {#RRGGBB} hex translation")
    void testBracketHex() {
        String translated = HexColors.translate("{#ff0000}RedText");
        assertTrue(translated.contains("§x§f§f§0§0§0§0RedText"));
    }

    @Test
    @DisplayName("Test MiniMessage tags to Component")
    void testMiniMessageToComponent() {
        Component comp = HexColors.translateToComponent("<green>Green <bold>Bold</bold> <#ff5555>Red</#ff5555>");
        assertNotNull(comp);
        String plain = HexColors.toPlainText(comp);
        assertEquals("Green Bold Red", plain);
    }

    @Test
    @DisplayName("Test MiniMessage click and hover events")
    void testClickAndHover() {
        Component comp = HexColors.translateToComponent("<click:run_command:\"/plm help\"><hover:show_text:\"Tooltip\">Click Me</hover></click>");
        assertNotNull(comp);
        assertEquals("Click Me", HexColors.toPlainText(comp));
    }

    @Test
    @DisplayName("Test hover event containing greater-than symbol inside quotes")
    void testHoverWithArrowSymbol() {
        Component comp = HexColors.translateToComponent("<hover:show_text:\"Click -> Next\">Button</hover>");
        assertNotNull(comp);
        assertEquals("Button", HexColors.toPlainText(comp));
    }

    @Test
    @DisplayName("Test click event with URL containing colons")
    void testClickWithUrlColon() {
        Component comp = HexColors.translateToComponent("<click:open_url:\"https://github.com/Test/Repo\">Link</click>");
        assertNotNull(comp);
        assertEquals("Link", HexColors.toPlainText(comp));
    }

    @Test
    @DisplayName("Test MiniMessage gradient rendering")
    void testGradient() {
        String legacy = HexColors.translate("<gradient:#ff0000:#00ff00>Gradient Text</gradient>");
        assertTrue(legacy.contains("§x"));
        assertEquals("Gradient Text", HexColors.stripColors(legacy));
    }

    @Test
    @DisplayName("Test multi-stop gradient with named colors")
    void testMultiStopGradient() {
        String legacy = HexColors.translate("<gradient:red:gold:yellow>Fire Text</gradient>");
        assertTrue(legacy.contains("§x"));
        assertEquals("Fire Text", HexColors.stripColors(legacy));
    }

    @Test
    @DisplayName("Test legacy {#RRGGBB>}gradient{#RRGGBB<} rendering")
    void testLegacyGradientFormat() {
        String legacy = HexColors.translate("{#ff0000>}Legacy Gradient{#00ff00<}");
        assertTrue(legacy.contains("§x"));
        assertEquals("Legacy Gradient", HexColors.stripColors(legacy));
    }

    @Test
    @DisplayName("Test rainbow rendering")
    void testRainbow() {
        String legacy = HexColors.translate("<rainbow>Rainbow Text</rainbow>");
        assertTrue(legacy.contains("§x"));
        assertEquals("Rainbow Text", HexColors.stripColors(legacy));
    }

    @Test
    @DisplayName("Test stripColors removes all formats while preserving issue numbers")
    void testStripColors() {
        String raw = "&a&lHello <bold><#ff0000>World</#ff0000></bold> &#123456Test <gradient:red:blue>Grad</gradient> Issue #999999";
        String clean = HexColors.stripColors(raw);
        assertEquals("Hello World Test Grad Issue #999999", clean);
    }

    @Test
    @DisplayName("Test stripTags removes only angle bracket tags")
    void testStripTags() {
        String raw = "&aHello <bold>World</bold>";
        String clean = HexColors.stripTags(raw);
        assertEquals("&aHello World", clean);
    }

    @Test
    @DisplayName("Test null and empty safety across HexColors methods")
    void testNullAndEmptySafety() {
        assertEquals("", HexColors.translate(null));
        assertEquals("", HexColors.translate(""));
        assertEquals("", HexColors.toMiniMessage(null));
        assertEquals("", HexColors.toMiniMessage(""));
        assertEquals(Component.empty(), HexColors.translateToComponent(null));
        assertEquals(Component.empty(), HexColors.translateToComponent(""));
        assertEquals("", HexColors.stripColors(null));
        assertEquals("", HexColors.stripColors(""));
        assertEquals("", HexColors.stripTags(null));
        assertEquals("", HexColors.stripTags(""));
        assertEquals("", HexColors.escapeTags(null));
        assertEquals("", HexColors.escapeTags(""));
        assertEquals("", HexColors.escapeLegacy(null));
        assertEquals("", HexColors.escapeLegacy(""));
        assertEquals("", HexColors.toLegacy(null));
        assertEquals(Component.empty(), HexColors.fromLegacy(null));
        assertEquals(Component.empty(), HexColors.fromLegacy(""));
    }

    @Test
    @DisplayName("Test escapeTags and escapeLegacy")
    void testEscaping() {
        assertEquals("\\<red\\>", HexColors.escapeTags("<red>"));
        assertEquals("\\&a", HexColors.escapeLegacy("&a"));
    }

    @Test
    @DisplayName("Test Component to legacy and from legacy")
    void testComponentLegacySerialization() {
        Component comp = Component.text("Hello ", NamedTextColor.RED).append(Component.text("World", NamedTextColor.GREEN));
        String legacy = HexColors.toLegacy(comp);
        Component deserialized = HexColors.fromLegacy(legacy);

        assertEquals("Hello World", HexColors.toPlainText(deserialized));
    }
}
