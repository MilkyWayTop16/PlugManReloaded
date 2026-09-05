package ru.milkyway.plugmanreloaded.commands;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public interface SubCommand {

    String getName();

    String getPermission();

    boolean isPlayerOnly();

    boolean execute(CommandSender sender, String[] args);

    default List<String> getAliases() {
        return Collections.emptyList();
    }

    default List<CommandFlag> getFlags() {
        return CommandFlags.forCommand(getName());
    }
}

