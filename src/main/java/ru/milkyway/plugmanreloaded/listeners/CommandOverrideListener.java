package ru.milkyway.plugmanreloaded.listeners;

import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.*;

public class CommandOverrideListener implements Listener {

    private static final Set<String> ALLOWED_NAMESPACES = Set.of(
            "bukkit", "minecraft", "paper", "purpur", "spigot", "leaf", "gale"
    );

    private static final List<String> LIST_FLAGS = List.of("-v", "--versions", "-j", "--jar");

    private final PlugManReloaded plugin;

    public CommandOverrideListener(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().isOverridePluginsCommand()) return;

        String[] tokens = extractPluginsCommandTokens(event.getMessage());
        if (tokens != null && canServeList(event.getPlayer())) {
            event.setCancelled(true);
            executeList(event.getPlayer(), tokens);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!plugin.getConfigManager().isOverridePluginsCommand()) return;

        String[] tokens = extractPluginsCommandTokens(event.getCommand());
        if (tokens != null && canServeList(event.getSender())) {
            event.setCancelled(true);
            executeList(event.getSender(), tokens);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        if (!plugin.getConfigManager().isOverridePluginsCommand()) return;

        String buffer = event.getBuffer();
        if (buffer == null || buffer.isBlank()) return;

        String raw = buffer.replaceFirst("^/+", "").trim();
        if (raw.isBlank()) return;

        String[] rawTokens = raw.split("\\s+");
        if (rawTokens.length == 0 || !isPluginsAlias(rawTokens[0])) return;

        CommandSender sender = event.getSender();
        if (!hasPermission(sender)) return;

        boolean endsWithSpace = buffer.endsWith(" ");
        String currentToken = endsWithSpace ? "" : rawTokens[rawTokens.length - 1];

        Set<String> usedTokens = new HashSet<>();
        int limit = endsWithSpace ? rawTokens.length : rawTokens.length - 1;
        for (int i = 1; i < limit; i++) {
            usedTokens.add(rawTokens[i].toLowerCase(Locale.ROOT));
        }

        List<String> candidates = new ArrayList<>();
        for (String flag : LIST_FLAGS) {
            if (!usedTokens.contains(flag.toLowerCase(Locale.ROOT))) {
                candidates.add(flag);
            }
        }

        List<String> matches = StringUtil.copyPartialMatches(currentToken, candidates, new ArrayList<>());
        event.setCompletions(matches);
    }

    public static @Nullable String[] extractPluginsCommandTokens(@Nullable String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return null;

        String raw = rawMessage.replaceFirst("^/+", "").trim();
        if (raw.isBlank()) return null;

        String[] tokens = raw.split("\\s+");
        if (tokens.length == 0 || !isPluginsAlias(tokens[0])) return null;

        return tokens;
    }

    private static boolean isPluginsAlias(String commandToken) {
        String lower = commandToken.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon > 0) {
            String ns = lower.substring(0, colon);
            String cmd = lower.substring(colon + 1);
            return ALLOWED_NAMESPACES.contains(ns) && (cmd.equals("pl") || cmd.equals("plugins"));
        }
        return lower.equals("pl") || lower.equals("plugins");
    }

    boolean canServeList(CommandSender sender) {
        return sender != null
                && (sender.hasPermission("plugmanreloaded.list") || sender.hasPermission("plugmanreloaded.admin"));
    }

    private boolean hasPermission(CommandSender sender) {
        return sender.hasPermission("plugmanreloaded.list")
                || sender.hasPermission("plugmanreloaded.admin")
                || sender.hasPermission("bukkit.command.plugins");
    }

    private void executeList(CommandSender sender, String[] tokens) {
        if (!hasPermission(sender)) {
            plugin.getConfigManager().executeActions(sender, "errors.no-permission");
            return;
        }

        String[] listArgs = new String[tokens.length];
        listArgs[0] = "list";
        if (tokens.length > 1) {
            System.arraycopy(tokens, 1, listArgs, 1, tokens.length - 1);
        }

        TaskScheduler.runSync(plugin, () -> plugin.getCommandsHandler().executeSubCommand(sender, "list", listArgs));
    }
}

