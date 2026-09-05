package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;

import java.util.Locale;

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
}

