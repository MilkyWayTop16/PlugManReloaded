package ru.milkyway.plugmanreloaded.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandContextTest {

    @Test
    @DisplayName("Test basic command and positional target parsing")
    void testBasicTarget() {
        String[] args = new String[]{"reload", "LuckPerms"};
        CommandContext ctx = CommandContext.parse(null, args);

        assertEquals("reload", ctx.subCommand());
        assertEquals("LuckPerms", ctx.target());
        assertFalse(ctx.isAll());
        assertFalse(ctx.isCancel());
        assertFalse(ctx.isConfirm());
        assertNull(ctx.token());
        assertTrue(ctx.flags().isEmpty());
    }

    @Test
    @DisplayName("Test flags before and after target")
    void testFlagsAnyPosition() {
        String[] args1 = new String[]{"reload", "-c", "LuckPerms"};
        CommandContext ctx1 = CommandContext.parse(null, args1);

        assertEquals("reload", ctx1.subCommand());
        assertEquals("LuckPerms", ctx1.target());
        assertTrue(ctx1.hasFlag("c"));
        assertTrue(ctx1.hasFlag("-c"));

        String[] args2 = new String[]{"reload", "LuckPerms", "--cascade", "-f"};
        CommandContext ctx2 = CommandContext.parse(null, args2);

        assertEquals("reload", ctx2.subCommand());
        assertEquals("LuckPerms", ctx2.target());
        assertTrue(ctx2.hasFlag("cascade"));
        assertTrue(ctx2.hasFlag("f"));
    }

    @Test
    @DisplayName("Test critical update single plugin with -y flag bugfix")
    void testUpdateFlagOrderingFix() {
        String[] args = new String[]{"update", "-y", "MiniMOTD"};
        CommandContext ctx = CommandContext.parse(null, args);

        assertEquals("update", ctx.subCommand());
        assertEquals("MiniMOTD", ctx.target());
        assertTrue(ctx.hasTarget());
        assertFalse(ctx.isAll());
        assertTrue(ctx.hasFlag("y"));
    }

    @Test
    @DisplayName("Test update all with -all or -a")
    void testUpdateAll() {
        String[] args = new String[]{"update", "-all", "-y"};
        CommandContext ctx = CommandContext.parse(null, args);

        assertEquals("update", ctx.subCommand());
        assertEquals("", ctx.target());
        assertFalse(ctx.hasTarget());
        assertTrue(ctx.isAll());
        assertTrue(ctx.hasFlag("y"));
    }

    @Test
    @DisplayName("Test options parsing like -s and --source")
    void testOptionsParsing() {
        String[] args = new String[]{"download", "-s", "spigot", "DeluxeMenus", "-y"};
        CommandContext ctx = CommandContext.parse(null, args);

        assertEquals("download", ctx.subCommand());
        assertEquals("DeluxeMenus", ctx.target());
        assertEquals("spigot", ctx.getOption("source"));
        assertTrue(ctx.hasFlag("y"));
    }

    @Test
    @DisplayName("Test cancel and token extraction")
    void testCancelAndToken() {
        String[] args = new String[]{"reload", "cancel", "MiniMOTD", "5ed3c363"};
        CommandContext ctx = CommandContext.parse(null, args);

        assertEquals("reload", ctx.subCommand());
        assertTrue(ctx.isCancel());
        assertEquals("MiniMOTD", ctx.target());
        assertEquals("5ed3c363", ctx.token());
    }

    @Test
    @DisplayName("Test multi-word target joining")
    void testMultiWordTarget() {
        String[] args = new String[]{"info", "Decent", "Holograms", "Plugin"};
        CommandContext ctx = CommandContext.parse(null, args);

        assertEquals("info", ctx.subCommand());
        assertEquals("Decent Holograms Plugin", ctx.target());
    }

    @Test
    @DisplayName("Test bundled flags like -cy or -fd")
    void testBundledFlags() {
        String[] args = new String[]{"delete", "-yd", "OldPlugin"};
        CommandContext ctx = CommandContext.parse(null, args);

        assertEquals("delete", ctx.subCommand());
        assertEquals("OldPlugin", ctx.target());
        assertTrue(ctx.hasFlag("y"));
        assertTrue(ctx.hasFlag("d"));
    }

    @Test
    @DisplayName("Длинное имя флага под одним тире не рассыпается на буквы")
    void testLongFlagUnderSingleDashDoesNotExplodeIntoLetters() {
        CommandContext force = CommandContext.parse(null, new String[]{"reload", "LuckPerms", "-force"});
        assertTrue(force.hasFlag("force"), "-force обязан распознаваться как флаг force");
        assertFalse(force.hasFlag("c"),
                "-force не должен включать каскад: буквы o и e не являются короткими флагами, "
                        + "значит это длинное имя, а не связка -f -o -r -c -e. Иначе «перезагрузить принудительно» "
                        + "молча превращается в «перезагрузить со всеми зависимыми»");
        assertFalse(force.hasFlag("r"), "-force не должен включать refresh");

        CommandContext noTarget = CommandContext.parse(null, new String[]{"reload", "-force"});
        assertFalse(noTarget.hasFlag("c") && !noTarget.hasTarget(),
                "/plm reload -force не должен попадать в ветку перезагрузки конфига плагина");

        CommandContext yes = CommandContext.parse(null, new String[]{"update", "LuckPerms", "-yes"});
        assertTrue(yes.hasFlag("yes"));
        assertFalse(yes.hasFlag("s"),
                "-yes не должен включать single и молча пропускать зависимые плагины");

        CommandContext refresh = CommandContext.parse(null, new String[]{"update", "-refresh"});
        assertTrue(refresh.hasFlag("refresh"));
        assertFalse(refresh.hasFlag("f"), "-refresh не должен включать force");
        assertFalse(refresh.hasFlag("s"), "-refresh не должен включать single");
    }

    @Test
    @DisplayName("Связка коротких флагов по-прежнему разбирается по буквам")
    void testShortFlagBundlesStillExplode() {
        CommandContext yf = CommandContext.parse(null, new String[]{"reload", "P", "-yf"});
        assertTrue(yf.hasFlag("y"));
        assertTrue(yf.hasFlag("f"));

        CommandContext fc = CommandContext.parse(null, new String[]{"reload", "P", "-fc"});
        assertTrue(fc.hasFlag("f"));
        assertTrue(fc.hasFlag("c"));
    }

    @Test
    @DisplayName("Кнопка {single-button} шлёт команду, которую парсер понимает")
    void testSingleButtonCommandParses() {
        CommandContext ctx = CommandContext.parse(null,
                new String[]{"update", "MyPlugin", "-y", "-single", "5ed3c363"});

        assertEquals("MyPlugin", ctx.target());
        assertEquals("5ed3c363", ctx.token());
        assertTrue(ctx.hasFlag("y") || ctx.hasFlag("yes"));
        assertTrue(ctx.hasFlag("s") || ctx.hasFlag("single"),
                "UpdateCommand читает single как hasFlag(\"s\") || hasFlag(\"single\")");
    }

    @Test
    @DisplayName("SHORT_FLAGS содержит ровно те буквы, что проверяет код команд")
    void testShortFlagsListMatchesCode() throws Exception {
        java.util.Set<Character> declared = CommandContext.SHORT_FLAGS;
        java.util.Set<Character> used = new java.util.TreeSet<>();

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("hasFlag\\(\"([a-z])\"\\)");
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (java.nio.file.Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                java.util.regex.Matcher m = pattern.matcher(
                        java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
                while (m.find()) {
                    used.add(m.group(1).charAt(0));
                }
            }
        }

        assertFalse(used.isEmpty(), "не удалось найти ни одной проверки hasFlag(\"x\") — тест устарел");
        assertEquals(used, new java.util.TreeSet<>(declared),
                "SHORT_FLAGS разъехался с кодом. Список решает, что считать связкой коротких флагов: "
                        + "буква, которую проверяет команда, но которой нет в списке, сделает связку вроде -yf "
                        + "неработающей, а лишняя буква вернёт баг с рассыпанием -force на -f -o -r -c -e");
    }

    @Test
    @DisplayName("Verify -s flag in update command does not swallow target plugin as source option")
    void testUpdateSingleFlagDoesNotSwallowTargetPlugin() {
        CommandContext ctx = CommandContext.parse(null, new String[]{"update", "-s", "LuckPerms"});
        assertEquals("update", ctx.subCommand());
        assertEquals("LuckPerms", ctx.target());
        assertTrue(ctx.hasFlag("s"));
        assertTrue(ctx.hasFlag("-s"));
        assertNull(ctx.getOption("source"));

        CommandContext reverse = CommandContext.parse(null, new String[]{"update", "LuckPerms", "-s"});
        assertEquals("update", reverse.subCommand());
        assertEquals("LuckPerms", reverse.target());
        assertTrue(reverse.hasFlag("s"));
        assertTrue(reverse.hasFlag("-s"));
        assertNull(reverse.getOption("source"));
    }
}
