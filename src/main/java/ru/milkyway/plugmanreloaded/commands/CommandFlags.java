package ru.milkyway.plugmanreloaded.commands;

import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class CommandFlags {

    private static final Map<String, List<Flag>> FLAGS = new HashMap<>();

    static {
        register("reload",
                Flag.of("cascade", "-c", "--cascade"),
                Flag.of("force", "-f", "--force"),
                Flag.of("config", "-c", "--config"));
        register("restart",
                Flag.of("cascade", "-c", "--cascade"),
                Flag.of("force", "-f", "--force"));
        register("unload",
                Flag.of("force", "-f", "--force"));
        register("disable",
                Flag.of("force", "-f", "--force"));
        register("delete",
                Flag.of("yes", "-y", "--yes"),
                Flag.of("force", "-f", "--force"),
                Flag.of("data", "-d", "--data"));
        register("update",
                Flag.of("yes", "-y", "--yes"),
                Flag.of("force", "-f", "--force"),
                Flag.of("refresh", "-r", "--refresh"),
                Flag.of("single", "-s", "--single"),
                Flag.of("cascade", "-c", "--cascade"));
        register("download",
                Flag.of("yes", "-y", "--yes"),
                Flag.of("force", "-f", "--force"),
                Flag.of("with-soft-deps", "-w", "--with-soft-deps"),
                Flag.of("select", "-g", "--get", "--select"),
                Flag.valueOption("source", "-s", "--source"));
        register("list",
                Flag.of("versions", "-v", "--versions"),
                Flag.of("jar", "-j", "--jar"));
    }

    private CommandFlags() {
    }

    private static void register(String command, Flag... flags) {
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

    public static List<Flag> forCommand(@Nullable String commandName) {
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
        List<Flag> flags = forCommand(subCommand);
        List<String> suggestions = new ArrayList<>();
        for (Flag flag : flags) {
            suggestions.addAll(flag.suggestUnused(usedTokens));
        }
        return suggestions;
    }

    private static final Flag ALL_FLAG = Flag.of("all", "-a", "-all", "--all");

    public static @Nullable Flag findFlag(@Nullable String subCommand, String token) {
        if (ALL_FLAG.matches(token)) {
            return ALL_FLAG;
        }
        if (subCommand != null) {
            for (Flag flag : forCommand(subCommand)) {
                if (flag.matches(token)) {
                    return flag;
                }
            }
            return null;
        }
        for (List<Flag> flags : FLAGS.values()) {
            for (Flag flag : flags) {
                if (flag.matches(token)) {
                    return flag;
                }
            }
        }
        return null;
    }

    public static final class Flag {
        private final String name;
        private final List<String> forms;
        private final boolean takesValue;

        public Flag(String name, List<String> forms, boolean takesValue) {
            this.name = name;
            this.forms = List.copyOf(forms);
            this.takesValue = takesValue;
        }

        public static Flag of(String name, String... forms) {
            return new Flag(name, List.of(forms), false);
        }

        public static Flag valueOption(String name, String... forms) {
            return new Flag(name, List.of(forms), true);
        }

        public String name() {
            return name;
        }

        public List<String> forms() {
            return forms;
        }

        public boolean takesValue() {
            return takesValue;
        }

        public boolean matches(String token) {
            if (token == null) {
                return false;
            }
            for (String form : forms) {
                if (form.equalsIgnoreCase(token)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isUsed(Set<String> usedTokens) {
            for (String form : forms) {
                if (usedTokens.contains(form.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        public List<String> suggestUnused(Set<String> usedTokens) {
            if (isUsed(usedTokens)) {
                return Collections.emptyList();
            }
            return forms;
        }
    }
}
