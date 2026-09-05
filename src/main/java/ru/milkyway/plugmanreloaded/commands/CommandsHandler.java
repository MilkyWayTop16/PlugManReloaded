package ru.milkyway.plugmanreloaded.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.sub.*;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.*;

public class CommandsHandler implements CommandExecutor {

    private final PlugManReloaded plugin;
    private final Map<String, SubCommand> subCommands = new HashMap<>();
    private final List<SubCommand> mainSubCommands = new ArrayList<>();

    public CommandsHandler(PlugManReloaded plugin) {
        this.plugin = plugin;

        register(new HelpCommand(plugin));
        register(new ListCommand(plugin));
        register(new InfoCommand(plugin));
        register(new LoadCommand(plugin));
        register(new UnloadCommand(plugin));
        register(new ReloadCommand(plugin));
        register(new RestartCommand(plugin));
        register(new EnableCommand(plugin));
        register(new DisableCommand(plugin));
        register(new UsageCommand(plugin));
        register(new DumpCommand(plugin));
        register(new HotSwapCommand(plugin));
        register(new ConfigSubCommand(plugin));
        register(new UpdateCommand(plugin));
        register(new DeleteCommand(plugin));
        register(new DownloadCommand(plugin));

    }

    private void register(SubCommand command) {
        mainSubCommands.add(command);
        subCommands.put(command.getName().toLowerCase(Locale.ROOT), command);
        for (String alias : command.getAliases()) {
            subCommands.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        TaskScheduler.runSync(plugin, () -> dispatch(sender, args));
        return true;
    }

    private void dispatch(CommandSender sender, String[] args) {
        if (args.length == 0) {
            SubCommand help = subCommands.get("help");
            if (help != null) {
                help.execute(sender, args);
            }
            return;
        }

        String subName = args[0].toLowerCase(Locale.ROOT);
        SubCommand subCommand = subCommands.get(subName);

        if (subCommand == null) {
            SubCommand help = subCommands.get("help");
            if (help != null) {
                help.execute(sender, args);
            }
            return;
        }

        subCommand.execute(sender, args);
    }

    public List<SubCommand> getMainSubCommands() {
        return mainSubCommands;
    }

    public SubCommand getSubCommand(String name) {
        return name != null ? subCommands.get(name.toLowerCase(Locale.ROOT)) : null;
    }

    public boolean executeSubCommand(CommandSender sender, String name, String[] args) {
        SubCommand sub = subCommands.get(name.toLowerCase(Locale.ROOT));
        if (sub != null) {
            return sub.execute(sender, args);
        }
        return false;
    }
}

