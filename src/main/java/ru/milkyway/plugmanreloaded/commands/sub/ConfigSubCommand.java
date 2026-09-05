package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;

import java.util.Map;

public class ConfigSubCommand extends AbstractSubCommand {

    public ConfigSubCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "config";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.config";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (ctx.argCount() < 2 || !ctx.target().equalsIgnoreCase("reload")) {
            sendAction(sender, "help.config");
            return true;
        }

        long start = System.currentTimeMillis();
        boolean success = plugin.getConfigManager().reload();
        long elapsed = System.currentTimeMillis() - start;

        if (success) {
            sendAction(sender, "config-reload.success", Map.of(
                    "time", String.valueOf(elapsed),
                    "ms", String.valueOf(elapsed)
            ));
        } else {
            sendAction(sender, "config-reload.failed", Map.of());
        }
        return true;
    }
}

