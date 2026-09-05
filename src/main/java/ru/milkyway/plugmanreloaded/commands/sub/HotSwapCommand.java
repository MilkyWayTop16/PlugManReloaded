package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;

import java.util.Locale;

public class HotSwapCommand extends AbstractSubCommand {

    public HotSwapCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "hotswap";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.hotswap";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (ctx.argCount() < 2 || !ctx.hasTarget()) {
            if (plugin.getHotSwapManager().isRunning()) {
                plugin.getHotSwapManager().stop();
                sendAction(sender, "hotswap.disabled");
            } else {
                plugin.getHotSwapManager().start();
                sendAction(sender, plugin.getHotSwapManager().isRunning() ? "hotswap.enabled" : "hotswap.start-failed");
            }
            return true;
        }

        String mode = ctx.target().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "on", "enable" -> {
                plugin.getHotSwapManager().start();
                sendAction(sender, plugin.getHotSwapManager().isRunning() ? "hotswap.enabled" : "hotswap.start-failed");
            }
            case "off", "disable" -> {
                plugin.getHotSwapManager().stop();
                sendAction(sender, "hotswap.disabled");
            }
            case "status" -> {
                boolean running = plugin.getHotSwapManager().isRunning();
                sendAction(sender, running ? "hotswap.status-on" : "hotswap.status-off");
            }
            default -> {
                sendAction(sender, "help.hotswap");
            }
        }

        return true;
    }
}

