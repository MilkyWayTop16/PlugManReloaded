package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class UsageCommand extends AbstractSubCommand {

    private static final int MAX_DISPLAY_RESULTS = 20;

    private record SearchResult(
            int score,
            boolean isCommand,
            String sortName,
            String formattedLine,
            String pluginName
    ) implements Comparable<SearchResult> {
        @Override
        public int compareTo(SearchResult o) {
            int cmp = Integer.compare(o.score, this.score);
            if (cmp != 0) return cmp;
            if (this.isCommand != o.isCommand) {
                return this.isCommand ? -1 : 1;
            }
            return this.sortName.compareToIgnoreCase(o.sortName);
        }
    }

    public UsageCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "usage";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.usage";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (ctx.argCount() < 2 || !ctx.hasTarget()) {
            sendAction(sender, "help.usage");
            return true;
        }

        String rawQuery = ctx.target();

        String query = rawQuery.toLowerCase(Locale.ROOT);
        String strippedQuery = query;
        if (strippedQuery.startsWith("//")) {
            strippedQuery = strippedQuery.substring(2).trim();
        } else if (strippedQuery.startsWith("/")) {
            strippedQuery = strippedQuery.substring(1).trim();
        }

        List<SearchResult> results = new ArrayList<>();
        Set<String> matchedPluginNames = new LinkedHashSet<>();
        Set<String> visitedKeys = new HashSet<>();

        searchCommands(query, strippedQuery, results, matchedPluginNames, visitedKeys);

        searchPermissions(query, strippedQuery, results, matchedPluginNames, visitedKeys);

        if (results.isEmpty()) {
            sendAction(sender, "usage.not-found", Map.of("query", rawQuery));
            return true;
        }

        Collections.sort(results);

        int totalFound = results.size();
        List<String> displayedLines = new ArrayList<>();
        int limit = Math.min(totalFound, MAX_DISPLAY_RESULTS);

        for (int i = 0; i < limit; i++) {
            displayedLines.add(results.get(i).formattedLine());
        }

        if (totalFound > MAX_DISPLAY_RESULTS) {
            int remaining = totalFound - MAX_DISPLAY_RESULTS;
            FileConfiguration cfg = plugin.getConfigManager().getMessagesConfig();
            String moreTemplate = cfg.getString("actions.usage.format.more",
                    "  &#FB8808▶ &f... and &#FB8808{remaining} &fmore matches. Refine your query!");
            displayedLines.add(moreTemplate.replace("{remaining}", String.valueOf(remaining)));
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("query", rawQuery);
        placeholders.put("count", String.valueOf(totalFound));
        placeholders.put("results", String.join("\n", displayedLines));
        placeholders.put("plugins", String.join("&7, &#FFFF00", matchedPluginNames));

        sendAction(sender, "usage.found", placeholders);
        return true;
    }

    private void searchCommands(String query, String strippedQuery, List<SearchResult> results, Set<String> matchedPluginNames, Set<String> visitedKeys) {
        for (Command cmd : getServerCommands()) {
            String name = cmd.getName();
            if (name == null || name.isBlank()) continue;

            String cleanName = name.startsWith("/") ? name.substring(1) : name;
            int bestScore = matchScore(name, strippedQuery);
            int cleanScore = matchScore(cleanName, strippedQuery);
            int score = Math.max(bestScore, cleanScore);

            if (cmd.getAliases() != null) {
                for (String alias : cmd.getAliases()) {
                    String cleanAlias = alias.startsWith("/") ? alias.substring(1) : alias;
                    int aScore = Math.max(matchScore(alias, strippedQuery), matchScore(cleanAlias, strippedQuery));
                    if (aScore > score) {
                        score = aScore;
                    }
                }
            }

            if (score > 0) {
                String key = "cmd:" + cleanName.toLowerCase(Locale.ROOT);
                if (!visitedKeys.add(key)) continue;

                Plugin owner = resolveCommandOwner(cmd);
                String pluginName = owner != null ? owner.getName() : resolveNamespaceOwner(cmd.getLabel());
                if (pluginName == null) {
                    pluginName = plugin.getConfigManager().text("actions.usage.owner.core-server");
                } else if (owner != null) {
                    matchedPluginNames.add(owner.getName());
                }

                String displayCmd = formatDisplayCommand(cleanName);
                String formatted = formatCommandLine(displayCmd, pluginName, cmd.getPermission(), cmd.getAliases());
                results.add(new SearchResult(score, true, cleanName, formatted, pluginName));
            }
        }

        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            Map<String, Map<String, Object>> cmds = p.getDescription().getCommands();
            if (cmds != null) {
                for (Map.Entry<String, Map<String, Object>> entry : cmds.entrySet()) {
                    String cmdName = entry.getKey();
                    String cleanName = cmdName.startsWith("/") ? cmdName.substring(1) : cmdName;

                    int score = Math.max(matchScore(cmdName, strippedQuery), matchScore(cleanName, strippedQuery));
                    if (score > 0) {
                        String key = "cmd:" + cleanName.toLowerCase(Locale.ROOT);
                        if (visitedKeys.add(key)) {
                            matchedPluginNames.add(p.getName());
                            Map<String, Object> details = entry.getValue();
                            String perm = details != null && details.get("permission") != null ? String.valueOf(details.get("permission")) : null;

                            String displayCmd = formatDisplayCommand(cleanName);
                            String formatted = formatCommandLine(displayCmd, p.getName(), perm, Collections.emptyList());
                            results.add(new SearchResult(score, true, cleanName, formatted, p.getName()));
                        }
                    }
                }
            }
        }
    }

    static String formatDisplayCommand(String name) {
        if (name == null) return "/";
        String clean = name.startsWith("/") ? name.substring(1) : name;
        return "/" + clean;
    }

    private void searchPermissions(String query, String strippedQuery, List<SearchResult> results, Set<String> matchedPluginNames, Set<String> visitedKeys) {
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            List<Permission> perms = p.getDescription().getPermissions();
            if (perms != null) {
                for (Permission perm : perms) {
                    String name = perm.getName();
                    int score = matchScore(name, strippedQuery);
                    if (score > 0) {
                        String key = "perm:" + name.toLowerCase(Locale.ROOT);
                        if (visitedKeys.add(key)) {
                            matchedPluginNames.add(p.getName());
                            String defVal = perm.getDefault() != null ? perm.getDefault().name().toLowerCase(Locale.ROOT) : "op";
                            String formatted = formatPermissionLine(name, p.getName(), defVal);
                            results.add(new SearchResult(score, false, name, formatted, p.getName()));
                        }
                    }
                }
            }
        }

        for (Command cmd : getServerCommands()) {
            String perm = cmd.getPermission();
            if (perm != null && !perm.isBlank()) {
                int score = matchScore(perm, strippedQuery);
                if (score > 0) {
                    String key = "perm:" + perm.toLowerCase(Locale.ROOT);
                    if (visitedKeys.add(key)) {
                        Plugin owner = resolveCommandOwner(cmd);
                        String ownerName = owner != null ? owner.getName() : resolvePermissionOwner(perm);
                        if (ownerName == null) {
                            ownerName = plugin.getConfigManager().text("actions.usage.owner.server-system");
                        } else {
                            matchedPluginNames.add(ownerName);
                        }
                        String formatted = formatPermissionLine(perm, ownerName, "op");
                        results.add(new SearchResult(score, false, perm, formatted, ownerName));
                    }
                }
            }
        }

        try {
            for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
                String name = perm.getName();
                int score = matchScore(name, strippedQuery);
                if (score > 0) {
                    String key = "perm:" + name.toLowerCase(Locale.ROOT);
                    if (visitedKeys.add(key)) {
                        String ownerName = resolvePermissionOwner(name);
                        if (ownerName != null) {
                            matchedPluginNames.add(ownerName);
                        } else {
                            ownerName = plugin.getConfigManager().text("actions.usage.owner.server-system");
                        }
                        String defVal = perm.getDefault() != null ? perm.getDefault().name().toLowerCase(Locale.ROOT) : "op";
                        String formatted = formatPermissionLine(name, ownerName, defVal);
                        results.add(new SearchResult(score, false, name, formatted, ownerName));
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("usagecommand.permission-search-failed", t);
        }
    }

    private static int matchScore(@Nullable String target, String query) {
        if (target == null || query == null || query.isBlank()) return -1;
        String t = target.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);

        if (t.equals(q)) {
            return 100;
        }
        if (t.startsWith(q)) {
            return 80;
        }

        String[] segments = t.split("[._\\-:/]");
        for (String segment : segments) {
            if (segment.equals(q)) {
                return 60;
            }
            if (segment.startsWith(q)) {
                return 40;
            }
        }

        if (q.length() >= 4 && t.contains(q)) {
            return 20;
        }

        return -1;
    }

    private String formatCommandLine(String displayCmd, String pluginName, String permission, List<String> aliases) {
        FileConfiguration cfg = plugin.getConfigManager().getMessagesConfig();
        String template;
        if (permission != null && !permission.isBlank()) {
            template = cfg.getString("actions.usage.format.command",
                    "  &#FFFF00◆ &fCommand &#FFFF00{command} &fby plugin &#FFFF00«{plugin}» &7(Permission: &#00FF5A{permission}&7)");
        } else {
            template = cfg.getString("actions.usage.format.command-no-perm",
                    "  &#FFFF00◆ &fCommand &#FFFF00{command} &fby plugin &#FFFF00«{plugin}»");
        }
        String aliasStr = (aliases != null && !aliases.isEmpty()) ? String.join(", ", aliases) : "";
        return template
                .replace("{command}", displayCmd)
                .replace("{plugin}", pluginName)
                .replace("{permission}", permission != null ? permission : "")
                .replace("{aliases}", aliasStr);
    }

    private String formatPermissionLine(String permName, String pluginName, String defVal) {
        FileConfiguration cfg = plugin.getConfigManager().getMessagesConfig();
        String template = cfg.getString("actions.usage.format.permission",
                "  &#00FF5A◆ &fPermission &#00FF5A{permission} &fof plugin &#FFFF00«{plugin}» &7(Default: &#FFFF00{default}&7)");
        String noneDefault = cfg.getString("actions.usage.no-default-value", "none");
        return template
                .replace("{permission}", permName)
                .replace("{plugin}", pluginName)
                .replace("{default}", defVal != null ? defVal : noneDefault);
    }

    private @Nullable Plugin resolveCommandOwner(Command cmd) {
        if (cmd instanceof PluginIdentifiableCommand pic) {
            return pic.getPlugin();
        }
        if (cmd instanceof PluginCommand pc) {
            return pc.getPlugin();
        }
        String label = cmd.getLabel();
        if (label != null && label.contains(":")) {
            String prefix = label.substring(0, label.indexOf(':'));
            Plugin p = Bukkit.getPluginManager().getPlugin(prefix);
            if (p != null) return p;
        }
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            Map<String, Map<String, Object>> cmds = p.getDescription().getCommands();
            if (cmds != null && (cmds.containsKey(cmd.getName()) || cmds.containsKey(cmd.getLabel()))) {
                return p;
            }
        }
        return null;
    }

    private @Nullable String resolveNamespaceOwner(String label) {
        if (label != null && label.contains(":")) {
            return label.substring(0, label.indexOf(':'));
        }
        return null;
    }

    private @Nullable String resolvePermissionOwner(@Nullable String permName) {
        if (permName == null) return null;
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (permName.toLowerCase(Locale.ROOT).startsWith(p.getName().toLowerCase(Locale.ROOT) + ".")) {
                return p.getName();
            }
        }
        return null;
    }

    private static Collection<Command> getServerCommands() {
        try {
            CommandMap map = Bukkit.getCommandMap();
            if (map instanceof SimpleCommandMap scm) {
                return scm.getCommands();
            }
            if (map != null) {
                Map<String, Command> known = ReflectionHelper.getFieldValue(SimpleCommandMap.class, map, "knownCommands");
                if (known != null) {
                    return known.values();
                }
            }
        } catch (Throwable t) {
            Log.debug("usagecommand.knowncommands-read-failed", t);
        }
        return Collections.emptyList();
    }

    public static List<String> getAllCommandAndPermissionNames() {
        Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Command cmd : getServerCommands()) {
            String name = cmd.getName();
            if (name != null && !name.isBlank()) {
                all.add(name);
                all.add(name.startsWith("/") ? name : "/" + name);
            }
            if (cmd.getAliases() != null) {
                for (String alias : cmd.getAliases()) {
                    if (alias != null && !alias.isBlank()) {
                        all.add(alias);
                        all.add(alias.startsWith("/") ? alias : "/" + alias);
                    }
                }
            }
            if (cmd.getPermission() != null && !cmd.getPermission().isBlank()) {
                all.add(cmd.getPermission());
            }
        }

        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            Map<String, Map<String, Object>> cmds = p.getDescription().getCommands();
            if (cmds != null) {
                for (Map.Entry<String, Map<String, Object>> entry : cmds.entrySet()) {
                    String cmdName = entry.getKey();
                    all.add(cmdName);
                    all.add(cmdName.startsWith("/") ? cmdName : "/" + cmdName);

                    Map<String, Object> details = entry.getValue();
                    if (details != null) {
                        Object permObj = details.get("permission");
                        if (permObj != null && !String.valueOf(permObj).isBlank()) {
                            all.add(String.valueOf(permObj));
                        }
                    }
                }
            }
            all.addAll(PluginMetaHelper.getPermissionNames(p));
        }

        try {
            for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
                if (perm.getName() != null && !perm.getName().isBlank()) {
                    all.add(perm.getName());
                }
            }
        } catch (Throwable t) {
            Log.debug("usagecommand.permissions-collect-failed", t);
        }

        return new ArrayList<>(all);
    }
}

