package ru.milkyway.plugmanreloaded.commands;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CommandTabCompleterTest {

    @Test
    @DisplayName("Verify flag aliases properly detect used flags and deduplicate them")
    void testFlagAliasesAndDeduplication() throws Exception {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        Method isFlagUsed = CommandTabCompleter.class.getDeclaredMethod("isFlagUsed", String.class, Set.class);
        isFlagUsed.setAccessible(true);

        Set<String> usedTokens = Set.of("-y");
        assertTrue((boolean) isFlagUsed.invoke(completer, "-y", usedTokens));
        assertTrue((boolean) isFlagUsed.invoke(completer, "--yes", usedTokens));
        assertFalse((boolean) isFlagUsed.invoke(completer, "-f", usedTokens));

        Set<String> cascadeTokens = Set.of("--cascade");
        assertTrue((boolean) isFlagUsed.invoke(completer, "-c", cascadeTokens));
        assertTrue((boolean) isFlagUsed.invoke(completer, "--cascade", cascadeTokens));
    }

    @Test
    @DisplayName("Verify filterUnused excludes already specified flags")
    void testFilterUnusedFlags() throws Exception {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        Method filterUnused = CommandTabCompleter.class.getDeclaredMethod("filterUnused", List.class, Set.class);
        filterUnused.setAccessible(true);

        List<String> flags = List.of("-y", "--yes", "-d", "--data");
        Set<String> used = Set.of("-y");

        @SuppressWarnings("unchecked")
        List<String> filtered = (List<String>) filterUnused.invoke(completer, flags, used);

        assertFalse(filtered.contains("-y"));
        assertFalse(filtered.contains("--yes"));
        assertTrue(filtered.contains("-d"));
        assertTrue(filtered.contains("--data"));
    }

    @Test
    @DisplayName("Verify download source completion when previous argument is -s or --source")
    void testDownloadSourceCompletion() throws Exception {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);

        List<String> results = completer.onTabComplete(null, null, "plm", new String[]{"download", "Essentials", "-s", ""});

        assertNotNull(results);
        assertTrue(results.contains("modrinth"));
        assertTrue(results.contains("hangar"));
        assertTrue(results.contains("spigot"));
        assertTrue(results.contains("github"));
    }

    @Test
    @DisplayName("Verify update subcommand suggests source action at argument index 3")
    void testUpdateSourceActionCompletion() throws Exception {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        Method getCandidates = CommandTabCompleter.class.getDeclaredMethod("getCandidates", String.class, int.class, String.class, Set.class, CommandSender.class);
        getCandidates.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) getCandidates.invoke(completer, "update", 3, "PluginName", Collections.emptySet(), null);
        assertTrue(candidates.contains("source"),
                "CommandTabCompleter обязан предлагать действие source для команды update при argLength == 3");
    }

    @Test
    @DisplayName("Verify that flags are not suggested at argLength 2 for commands requiring target plugins")
    void testPositionalFlagSeparation() throws Exception {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        Method getCandidates = CommandTabCompleter.class.getDeclaredMethod("getCandidates", String.class, int.class, String.class, Set.class, CommandSender.class);
        getCandidates.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> deleteArg2 = (List<String>) getCandidates.invoke(completer, "delete", 2, "delete", Collections.emptySet(), null);
        assertFalse(deleteArg2.contains("-y"), "delete при argLength 2 не должен содержать -y");
        assertFalse(deleteArg2.contains("-d"), "delete при argLength 2 не должен содержать -d");

        @SuppressWarnings("unchecked")
        List<String> deleteArg3 = (List<String>) getCandidates.invoke(completer, "delete", 3, "PluginName", Collections.emptySet(), null);
        assertTrue(deleteArg3.contains("-y"), "delete при argLength 3 обязан содержать -y");
        assertTrue(deleteArg3.contains("-d"), "delete при argLength 3 обязан содержать -d");

        @SuppressWarnings("unchecked")
        List<String> unloadArg2 = (List<String>) getCandidates.invoke(completer, "unload", 2, "unload", Collections.emptySet(), null);
        assertFalse(unloadArg2.contains("-f"), "unload при argLength 2 не должен содержать -f");

        @SuppressWarnings("unchecked")
        List<String> unloadArg3 = (List<String>) getCandidates.invoke(completer, "unload", 3, "PluginName", Collections.emptySet(), null);
        assertTrue(unloadArg3.contains("-f"), "unload при argLength 3 обязан содержать -f");
        assertTrue(unloadArg3.contains("-y"), "unload при argLength 3 обязан содержать -y");

        @SuppressWarnings("unchecked")
        List<String> restartArg2 = (List<String>) getCandidates.invoke(completer, "restart", 2, "restart", Collections.emptySet(), null);
        assertFalse(restartArg2.contains("-c"), "restart при argLength 2 не должен содержать -c");
        assertFalse(restartArg2.contains("-f"), "restart при argLength 2 не должен содержать -f");

        @SuppressWarnings("unchecked")
        List<String> restartArg3 = (List<String>) getCandidates.invoke(completer, "restart", 3, "PluginName", Collections.emptySet(), null);
        assertTrue(restartArg3.contains("-c"), "restart при argLength 3 обязан содержать -c");
        assertTrue(restartArg3.contains("-f"), "restart при argLength 3 обязан содержать -f");

        @SuppressWarnings("unchecked")
        List<String> downloadArg2 = (List<String>) getCandidates.invoke(completer, "download", 2, "download", Collections.emptySet(), null);
        assertFalse(downloadArg2.contains("-y"), "download при argLength 2 не должен содержать -y");
        assertFalse(downloadArg2.contains("-w"), "download при argLength 2 не должен содержать -w");

        @SuppressWarnings("unchecked")
        List<String> downloadArg3 = (List<String>) getCandidates.invoke(completer, "download", 3, "PluginName", Collections.emptySet(), null);
        assertTrue(downloadArg3.contains("-y"), "download при argLength 3 обязан содержать -y");
        assertTrue(downloadArg3.contains("-w"), "download при argLength 3 обязан содержать -w");
    }

    @Test
    @DisplayName("Verify null safety on empty/incomplete contexts")
    void testNullSafety() {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        assertDoesNotThrow(() -> completer.onTabComplete(null, null, "plm", new String[0]));
        assertDoesNotThrow(() -> completer.onTabComplete(null, null, "plm", new String[]{"load", ""}));
        assertDoesNotThrow(() -> completer.onTabComplete(null, null, "plm", new String[]{"unload", ""}));
        assertDoesNotThrow(() -> completer.onTabComplete(null, null, "plm", new String[]{"reload", ""}));
        assertDoesNotThrow(() -> completer.onTabComplete(null, null, "plm", new String[]{"update", ""}));
    }

    @Test
    @DisplayName("Verify update subcommand does not suggest download sources when preceded by -s or --single")
    void testUpdateDoesNotSuggestDownloadSourcesAfterSingleFlag() {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        List<String> results = completer.onTabComplete(null, null, "plm", new String[]{"update", "LuckPerms", "-s", ""});
        assertNotNull(results);
        assertFalse(results.contains("modrinth"));
        assertFalse(results.contains("hangar"));
        assertFalse(results.contains("spigot"));
        assertFalse(results.contains("github"));
    }

    @Test
    @DisplayName("Verify aliases like rl, del, dl are recognized as their canonical subcommands")
    void testSubCommandAliasesInTabCompleter() {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        List<String> dlSources = completer.onTabComplete(null, null, "plm", new String[]{"dl", "query", "-s", ""});
        assertNotNull(dlSources);
        assertTrue(dlSources.contains("modrinth"));
        assertTrue(dlSources.contains("hangar"));
    }

    @Test
    @DisplayName("Verify using -s in update deduplicates both -s and --single")
    void testDeduplicationInUpdateCommand() throws Exception {
        CommandTabCompleter completer = new CommandTabCompleter(null, null);
        Method getCandidates = CommandTabCompleter.class.getDeclaredMethod("getCandidates", String.class, int.class, String.class, Set.class, CommandSender.class);
        getCandidates.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> withSingleUsed = (List<String>) getCandidates.invoke(completer, "update", 3, "PluginName", Set.of("-s"), null);
        assertFalse(withSingleUsed.contains("-s"));
        assertFalse(withSingleUsed.contains("--single"));
        assertTrue(withSingleUsed.contains("-r"));
        assertTrue(withSingleUsed.contains("--refresh"));
    }
}
