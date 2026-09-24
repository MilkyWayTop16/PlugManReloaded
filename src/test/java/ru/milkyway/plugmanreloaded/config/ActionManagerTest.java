package ru.milkyway.plugmanreloaded.config;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.managers.ActionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ActionManagerTest {

    @BeforeAll
    static void initServer() {
        ru.milkyway.plugmanreloaded.BukkitServerMock.ensureInitialized();
    }

    @Test
    @DisplayName("Verify placeholder aliases (author/authors, deps/dependencies, from/current, to/latest)")
    void testPlaceholderAliases() throws Exception {
        ActionManager actionManager = new ActionManager(null, null);
        Method apply = ActionManager.class.getDeclaredMethod("applyPlaceholders", String.class, Map.class);
        apply.setAccessible(true);

        String template = "Plugin by {author}, deps: {deps}, update {current} -> {to}";
        Map<String, String> placeholders = Map.of(
                "authors", "MilkyWay",
                "dependencies", "Vault, ProtocolLib",
                "from", "1.0.0",
                "latest", "1.1.0"
        );

        String result = (String) apply.invoke(actionManager, template, placeholders);
        assertEquals("Plugin by MilkyWay, deps: Vault, ProtocolLib, update 1.0.0 -> 1.1.0", result);
    }

    @Test
    @DisplayName("Verify version placeholders clean leading 'v' to avoid double 'vv' in templates")
    void testVersionPlaceholderCleaning() throws Exception {
        ActionManager actionManager = new ActionManager(null, null);
        Method apply = ActionManager.class.getDeclaredMethod("applyPlaceholders", String.class, Map.class);
        apply.setAccessible(true);

        String template = "Updated to v{to} (was v{from})";
        Map<String, String> placeholders = Map.of(
                "to", "v2.5.0",
                "from", "V1.0.0"
        );

        String result = (String) apply.invoke(actionManager, template, placeholders);
        assertEquals("Updated to v2.5.0 (was v1.0.0)", result);
    }

    @Test
    @DisplayName("Verify multiline placeholder indentation preserves formatting")
    void testMultilinePlaceholderIndentation() throws Exception {
        ActionManager actionManager = new ActionManager(null, null);
        Method apply = ActionManager.class.getDeclaredMethod("applyPlaceholders", String.class, Map.class);
        apply.setAccessible(true);

        String template = "Details:\n  - {list}";
        Map<String, String> placeholders = Map.of(
                "list", "Item1\nItem2\nItem3"
        );

        String result = (String) apply.invoke(actionManager, template, placeholders);
        assertEquals("Details:\n  - Item1\n  - Item2\n  - Item3", result);
    }

    @Test
    @DisplayName("Verify action prefix parsing for all supported action types")
    void testActionTypeParsing() throws Exception {
        Method parseLine = ActionManager.class.getDeclaredMethod("parseLine", String.class);
        parseLine.setAccessible(true);

        ActionManager.ParsedAction msg = (ActionManager.ParsedAction) parseLine.invoke(null, "[message] Hello world");
        assertEquals("message", msg.type());
        assertEquals("Hello world", msg.content());

        ActionManager.ParsedAction actionbar = (ActionManager.ParsedAction) parseLine.invoke(null, "[actionbar] Reloading...");
        assertEquals("actionbar", actionbar.type());
        assertEquals("Reloading...", actionbar.content());

        ActionManager.ParsedAction sound = (ActionManager.ParsedAction) parseLine.invoke(null, "[sound] ENTITY_PLAYER_LEVELUP 1.0 1.0");
        assertEquals("sound", sound.type());
        assertEquals("ENTITY_PLAYER_LEVELUP 1.0 1.0", sound.content());

        ActionManager.ParsedAction plain = (ActionManager.ParsedAction) parseLine.invoke(null, "Plain message without tag");
        assertEquals("message", plain.type());
        assertEquals("Plain message without tag", plain.content());
    }

    @Test
    @DisplayName("Verify playSound supports fallback to custom string sound keys")
    void testSoundFallbackInCode() throws Exception {
        ActionManager actionManager = new ActionManager(null, null);
        Method playSound = ActionManager.class.getDeclaredMethod("playSound", org.bukkit.entity.Player.class, String.class);
        playSound.setAccessible(true);

        boolean[] soundPlayed = new boolean[1];
        String[] playedKey = new String[1];
        float[] playedVol = new float[1];
        float[] playedPitch = new float[1];

        org.bukkit.entity.Player player = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                ActionManagerTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("playSound") && args != null && args.length >= 4 && args[1] instanceof String) {
                        soundPlayed[0] = true;
                        playedKey[0] = (String) args[1];
                        playedVol[0] = (Float) args[2];
                        playedPitch[0] = (Float) args[3];
                        return null;
                    }
                    if (method.getName().equals("isOnline")) {
                        return true;
                    }
                    if (method.getName().equals("getLocation")) {
                        return null;
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                });

        playSound.invoke(actionManager, player, "custom.my_resource_pack_sound 1.5 0.8");

        assertTrue(soundPlayed[0]);
        assertEquals("custom.my_resource_pack_sound", playedKey[0]);
        assertEquals(1.5f, playedVol[0]);
        assertEquals(0.8f, playedPitch[0]);
    }
}
