package ru.milkyway.plugmanreloaded.commands;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;

import java.util.*;

public final class CommandContext {

    private final CommandSender sender;
    private final String subCommand;
    private final String[] rawArgs;
    private final Set<String> flags;
    private final Map<String, String> options;
    private final List<String> positionalArgs;
    private final String target;
    private final String token;
    private final boolean isAll;
    private final boolean isCancel;
    private final boolean isConfirm;

    private CommandContext(CommandSender sender,
                           String subCommand,
                           String[] rawArgs,
                           Set<String> flags,
                           Map<String, String> options,
                           List<String> positionalArgs,
                           String target,
                           String token,
                           boolean isAll,
                           boolean isCancel,
                           boolean isConfirm) {
        this.sender = sender;
        this.subCommand = subCommand;
        this.rawArgs = rawArgs != null ? rawArgs : new String[0];
        this.flags = Collections.unmodifiableSet(flags);
        this.options = Collections.unmodifiableMap(options);
        this.positionalArgs = Collections.unmodifiableList(positionalArgs);
        this.target = target;
        this.token = token;
        this.isAll = isAll;
        this.isCancel = isCancel;
        this.isConfirm = isConfirm;
    }

    static final Set<Character> SHORT_FLAGS = Set.of('c', 'd', 'f', 'g', 'j', 'r', 's', 'v', 'w', 'y');

    private static boolean isShortFlagBundle(String bundle) {
        for (int i = 0; i < bundle.length(); i++) {
            if (!SHORT_FLAGS.contains(bundle.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static CommandContext parse(CommandSender sender, @Nullable String[] args) {
        return parse(sender, args, null);
    }

    public static CommandContext parse(CommandSender sender, @Nullable String[] args, @Nullable SubCommand command) {
        if (args == null || args.length == 0) {
            return new CommandContext(sender, "", new String[0], Collections.emptySet(),
                    Collections.emptyMap(), Collections.emptyList(), "", null, false, false, false);
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        boolean takesSource = command != null
                ? command.getFlags().stream().anyMatch(f -> f.takesValue() && f.name().equals("source"))
                : CommandFlags.takesSourceOption(subCommand);

        Set<String> flags = new HashSet<>();
        Map<String, String> options = new HashMap<>();
        List<String> positional = new ArrayList<>();
        String extractedToken = null;
        boolean all = false;
        boolean cancel = false;
        boolean confirm = false;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }

            if (arg.equalsIgnoreCase("cancel") || arg.equalsIgnoreCase("-cancel") || arg.equalsIgnoreCase("--cancel")) {
                cancel = true;
                continue;
            }

            if (arg.equalsIgnoreCase("confirm") || arg.equalsIgnoreCase("-confirm") || arg.equalsIgnoreCase("--confirm")) {
                confirm = true;
                continue;
            }

            if (arg.equalsIgnoreCase("-all") || arg.equalsIgnoreCase("-a")
                    || arg.equalsIgnoreCase("--all") || arg.equalsIgnoreCase("all")
                    || arg.equals("*")) {
                all = true;
                flags.add("all");
                flags.add("a");
                continue;
            }

            if (takesSource && (arg.equalsIgnoreCase("-s") || arg.equalsIgnoreCase("--source"))
                    && i + 1 < args.length && !args[i + 1].startsWith("-")) {
                options.put("source", args[++i].toLowerCase(Locale.ROOT));
                flags.add("s");
                flags.add("source");
                continue;
            }

            if (arg.startsWith("--") && arg.length() > 2) {
                String flagName = arg.substring(2).toLowerCase(Locale.ROOT);
                flags.add(flagName);
                continue;
            }

            if (arg.startsWith("-") && arg.length() > 1) {
                String bundle = arg.substring(1).toLowerCase(Locale.ROOT);
                flags.add(bundle);
                if (isShortFlagBundle(bundle)) {
                    for (int c = 0; c < bundle.length(); c++) {
                        flags.add(String.valueOf(bundle.charAt(c)));
                    }
                }
                continue;
            }

            if (ConfirmationManager.looksLikeToken(arg)) {
                extractedToken = arg;
                continue;
            }

            positional.add(arg);
        }

        String target = String.join(" ", positional).trim();
        if (target.isEmpty() && extractedToken != null && !cancel && !confirm && flags.isEmpty()) {
            target = extractedToken;
        }

        return new CommandContext(
                sender,
                subCommand,
                args,
                flags,
                options,
                positional,
                target,
                extractedToken,
                all,
                cancel,
                confirm
        );
    }

    public CommandSender sender() {
        return sender;
    }

    public String subCommand() {
        return subCommand;
    }

    public String[] rawArgs() {
        return rawArgs;
    }

    public Set<String> flags() {
        return flags;
    }

    public Map<String, String> options() {
        return options;
    }

    public List<String> positionalArgs() {
        return positionalArgs;
    }

    public String target() {
        return target;
    }

    public String token() {
        return token;
    }

    public boolean isAll() {
        return isAll;
    }

    public boolean isCancel() {
        return isCancel;
    }

    public boolean isConfirm() {
        return isConfirm;
    }

    public boolean hasTarget() {
        return !target.isEmpty();
    }

    public boolean hasFlag(@Nullable String flag) {
        if (flag == null) return false;
        String clean = flag.startsWith("--") ? flag.substring(2) : (flag.startsWith("-") ? flag.substring(1) : flag);
        return flags.contains(clean.toLowerCase(Locale.ROOT));
    }

    public String getOption(String key) {
        return options.get(key != null ? key.toLowerCase(Locale.ROOT) : null);
    }

    public String getOption(String key, String def) {
        String val = getOption(key);
        return val != null ? val : def;
    }

    public int argCount() {
        return rawArgs.length;
    }
}

