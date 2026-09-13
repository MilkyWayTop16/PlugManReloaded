package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.commands.SubCommand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HelpCommand extends AbstractSubCommand {

    public HelpCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.help";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (ctx.hasTarget()) {
            String sub = ctx.target().toLowerCase(Locale.ROOT);
            if (plugin.getConfigManager().getMessagesConfig().contains("actions.help." + sub)) {
                sendAction(sender, "help." + sub);
                return true;
            }
        }
        sendAction(sender, "help.main");
        return true;
    }

    @Override
    public List<String> tabCandidates(int argLength, String previousToken, Set<String> usedTokens, CommandSender sender) {
        if (argLength == 2 && plugin != null && plugin.getCommandHandler() != null) {
            Set<String> allowed = new LinkedHashSet<>();
            for (SubCommand sub : plugin.getCommandHandler().getMainSubCommands()) {
                if (sub.getPermission() == null || sub.getPermission().isBlank() || sender == null || sender.hasPermission(sub.getPermission()) || sender.hasPermission("plugmanreloaded.admin")) {
                    allowed.add(sub.getName());
                }
            }
            return new ArrayList<>(allowed);
        }
        return Collections.emptyList();
    }
}

