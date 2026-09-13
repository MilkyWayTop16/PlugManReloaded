package ru.milkyway.plugmanreloaded.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

import java.util.*;

public class CommandTabCompleter implements TabCompleter {

    private final PlugManReloaded plugin;
    private final CommandHandler commandHandler;

    public CommandTabCompleter(PlugManReloaded plugin, CommandHandler commandHandler) {
        this.plugin = plugin;
        this.commandHandler = commandHandler != null ? commandHandler : new CommandHandler(plugin);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return match(args[0], subCommandNames(sender));
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String currentToken = args[args.length - 1];
        String previousToken = args.length >= 2 ? (args[args.length - 2] != null ? args[args.length - 2].toLowerCase(Locale.ROOT) : "") : "";

        Set<String> usedTokens = new HashSet<>();
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i] != null) {
                usedTokens.add(args[i].toLowerCase(Locale.ROOT));
            }
        }

        List<String> candidates = getCandidates(sub, args.length, previousToken, usedTokens, sender);
        return match(currentToken, candidates);
    }

    private List<String> getCandidates(String sub, int argLength, String previousToken, Set<String> usedTokens, CommandSender sender) {
        SubCommand subCommand = findSubCommand(sub);
        if (subCommand instanceof AbstractSubCommand abstractSub) {
            return abstractSub.tabCandidates(argLength, previousToken, usedTokens, sender);
        }
        return Collections.emptyList();
    }

    private boolean isFlagUsed(String flag, Set<String> usedTokens) {
        return isFlagUsed(null, flag, usedTokens);
    }

    private boolean isFlagUsed(@Nullable String subCommand, String flag, Set<String> usedTokens) {
        String lower = flag.toLowerCase(Locale.ROOT);
        if (usedTokens.contains(lower)) {
            return true;
        }
        CommandFlags.Flag cmdFlag = CommandFlags.findFlag(subCommand, lower);
        return cmdFlag != null && cmdFlag.isUsed(usedTokens);
    }

    private List<String> filterUnused(List<String> flags, Set<String> usedTokens) {
        return filterUnused(null, flags, usedTokens);
    }

    private List<String> filterUnused(@Nullable String subCommand, List<String> flags, Set<String> usedTokens) {
        List<String> result = new ArrayList<>();
        for (String flag : flags) {
            if (!isFlagUsed(subCommand, flag, usedTokens)) {
                result.add(flag);
            }
        }
        return result;
    }

    private SubCommand findSubCommand(String sub) {
        String canonical = CommandFlags.canonicalCommand(sub);
        return commandHandler != null ? commandHandler.getSubCommand(canonical) : null;
    }

    private List<String> subCommandNames(CommandSender sender) {
        if (commandHandler == null) return Collections.emptyList();
        Set<String> allowed = new LinkedHashSet<>();
        for (SubCommand sub : commandHandler.getMainSubCommands()) {
            if (sub.getPermission() == null || sub.getPermission().isBlank() || sender == null || sender.hasPermission(sub.getPermission()) || sender.hasPermission("plugmanreloaded.admin")) {
                allowed.add(sub.getName());
            }
        }
        return new ArrayList<>(allowed);
    }

    private List<String> match(String token, List<String> candidates) {
        if (candidates.isEmpty()) return Collections.emptyList();
        return StringUtil.copyPartialMatches(token, candidates, new ArrayList<>(candidates.size()));
    }
}