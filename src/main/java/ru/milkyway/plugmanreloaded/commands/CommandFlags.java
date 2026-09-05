package ru.milkyway.plugmanreloaded.commands;

import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class CommandFlags {

    private static final Map<String, List<CommandFlag>> FLAGS = new HashMap<>();

    static {
        register("reload",
                CommandFlag.of("cascade", "-c", "--cascade"),
                CommandFlag.of("force", "-f", "--force"),
                CommandFlag.of("config", "-c", "--config"));
        register("restart",
                CommandFlag.of("cascade", "-c", "--cascade"),
                CommandFlag.of("force", "-f", "--force"));
        register("unload",
                CommandFlag.of("force", "-f", "--force"));
        register("disable",
                CommandFlag.of("force", "-f", "--force"));
        register("delete",
                CommandFlag.of("yes", "-y", "--yes"),
                CommandFlag.of("force", "-f", "--force"),
                CommandFlag.of("data", "-d", "--data"));
        register("update",
                CommandFlag.of("yes", "-y", "--yes"),
                CommandFlag.of("force", "-f", "--force"),
                CommandFlag.of("refresh", "-r", "--refresh"),
                CommandFlag.of("single", "-s", "--single"),
                CommandFlag.of("cascade", "-c", "--cascade"));
        register("download",
                CommandFlag.of("yes", "-y", "--yes"),
                CommandFlag.of("force", "-f", "--force"),
                CommandFlag.of("with-soft-deps", "-w", "--with-soft-deps"),
                CommandFlag.of("select", "-g", "--get", "--select"),
                CommandFlag.valueOption("source", "-s", "--source"));
        register("list",
                CommandFlag.of("versions", "-v", "--versions"),
                CommandFlag.of("jar", "-j", "--jar"));
    }

    private CommandFlags() {
    }

    private static void register(String command, CommandFlag... flags) {
        FLAGS.put(command, List.of(flags));
    }

    public static String canonicalCommand(@Nullable String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "rl" -> "reload";
            case "del" -> "delete";
            case "dl", "get", "install" -> "download";
            case "lookup" -> "info";
            default -> lower;
        };
    }

    public static List<CommandFlag> forCommand(@Nullable String commandName) {
        if (commandName == null) {
            return Collections.emptyList();
        }
        String canonical = canonicalCommand(commandName);
        return FLAGS.getOrDefault(canonical, Collections.emptyList());
    }

    public static boolean takesSourceOption(@Nullable String subCommand) {
        return canonicalCommand(subCommand).equals("download");
    }

    public static List<String> suggestFlags(@Nullable String subCommand, Set<String> usedTokens) {
        List<CommandFlag> flags = forCommand(subCommand);
        List<String> suggestions = new ArrayList<>();
        for (CommandFlag flag : flags) {
            suggestions.addAll(flag.suggestUnused(usedTokens));
        }
        return suggestions;
    }

    private static final CommandFlag ALL_FLAG = CommandFlag.of("all", "-a", "-all", "--all");

    public static @Nullable CommandFlag findFlag(@Nullable String subCommand, String token) {
        if (ALL_FLAG.matches(token)) {
            return ALL_FLAG;
        }
        if (subCommand != null) {
            for (CommandFlag flag : forCommand(subCommand)) {
                if (flag.matches(token)) {
                    return flag;
                }
            }
            return null;
        }
        for (List<CommandFlag> flags : FLAGS.values()) {
            for (CommandFlag flag : flags) {
                if (flag.matches(token)) {
                    return flag;
                }
            }
        }
        return null;
    }
}
