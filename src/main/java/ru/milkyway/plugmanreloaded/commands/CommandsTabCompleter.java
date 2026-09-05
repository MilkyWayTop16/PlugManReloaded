package ru.milkyway.plugmanreloaded.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StringUtil;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.sub.UsageCommand;
import ru.milkyway.plugmanreloaded.configs.MainConfig;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.function.Predicate;

public class CommandsTabCompleter implements TabCompleter {

    private static final List<String> DOWNLOAD_SOURCES = List.of("modrinth", "hangar", "spigot", "github");
    private static final List<String> HOTSWAP_MODES = List.of("on", "off", "status");


    private final PlugManReloaded plugin;
    private final CommandsHandler commandsHandler;

    public CommandsTabCompleter(PlugManReloaded plugin, CommandsHandler commandsHandler) {
        this.plugin = plugin;
        this.commandsHandler = commandsHandler;
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
        String previousToken = args.length >= 2 ? args[args.length - 2].toLowerCase(Locale.ROOT) : "";

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
        LifecycleManager lifecycle = plugin != null ? plugin.getPluginLifecycleManager() : null;
        String canonical = CommandFlags.canonicalCommand(sub);

        if (canonical.equals("download") && (previousToken.equals("-s") || previousToken.equals("--source"))) {
            return filterUnused("download", DOWNLOAD_SOURCES, usedTokens);
        }

        boolean firstArgument = argLength == 2;
        return switch (canonical) {
            case "help" -> firstArgument ? subCommandNames(sender) : Collections.emptyList();

            case "load" -> firstArgument
                    ? withAllFlag(usedTokens, lifecycle != null ? lifecycle.getLoadableNames(plugin != null && plugin.getConfigManager().isUseJarFileNames()) : Collections.emptyList())
                    : Collections.emptyList();

            case "enable" -> firstArgument
                    ? withAllFlag(usedTokens, loadedPlugins(p -> !p.isEnabled()))
                    : Collections.emptyList();

            case "disable" -> firstArgument
                    ? withAllFlag(usedTokens, loadedPlugins(p -> p.isEnabled() && (lifecycle == null || !lifecycle.isProtected(p))))
                    : CommandFlags.suggestFlags("disable", usedTokens);

            case "unload" -> firstArgument
                    ? withAllFlag(usedTokens, loadedPlugins(p -> lifecycle == null || !lifecycle.isProtected(p)))
                    : CommandFlags.suggestFlags("unload", usedTokens);

            case "reload" -> firstArgument
                    ? reloadCandidates(usedTokens, lifecycle, sender)
                    : CommandFlags.suggestFlags("reload", usedTokens);

            case "restart" -> firstArgument
                    ? withAllFlag(usedTokens, loadedPlugins(p -> lifecycle == null || !lifecycle.isProtected(p)))
                    : CommandFlags.suggestFlags("restart", usedTokens);

            case "delete" -> firstArgument
                    ? new ArrayList<>(allDeletablePlugins(lifecycle))
                    : CommandFlags.suggestFlags("delete", usedTokens);

            case "update" -> updateCandidates(argLength, usedTokens, lifecycle, sender);

            case "download" -> firstArgument
                    ? new ArrayList<>(downloadSuggestions())
                    : CommandFlags.suggestFlags("download", usedTokens);

            case "list" -> CommandFlags.suggestFlags("list", usedTokens);

            case "info" -> firstArgument ? allInspectablePlugins(lifecycle) : Collections.emptyList();

            case "usage" -> firstArgument ? UsageCommand.getAllCommandAndPermissionNames() : Collections.emptyList();

            case "hotswap" -> firstArgument ? HOTSWAP_MODES : Collections.emptyList();

            case "config" -> firstArgument ? List.of("reload") : Collections.emptyList();

            default -> Collections.emptyList();
        };
    }

    private List<String> withAllFlag(Set<String> usedTokens, Collection<String> plugins) {
        List<String> list = new ArrayList<>();
        if (!isFlagUsed("-all", usedTokens)) {
            list.add("-all");
            list.add("-a");
        }
        list.addAll(plugins);
        return list;
    }

    private List<String> reloadCandidates(Set<String> usedTokens, @Nullable LifecycleManager lifecycle, CommandSender sender) {
        List<String> list = withAllFlag(usedTokens, loadedPlugins(p -> lifecycle == null || !lifecycle.isProtected(p)));
        boolean mayReloadConfig = sender != null && sender.hasPermission("plugmanreloaded.config");
        if (mayReloadConfig && !isFlagUsed("reload", "-c", usedTokens) && !isFlagUsed("reload", "--config", usedTokens)) {
            list.add("--config");
            list.add("-c");
        }
        return list;
    }

    private List<String> updateCandidates(int argLength, Set<String> usedTokens,
                                          @Nullable LifecycleManager lifecycle, CommandSender sender) {
        if (argLength == 2) {
            List<String> list = withAllFlag(usedTokens, CommandFlags.suggestFlags("update", usedTokens));
            list.addAll(allDeletablePlugins(lifecycle));
            return list;
        }

        List<String> list = new ArrayList<>(CommandFlags.suggestFlags("update", usedTokens));
        boolean maySetSource = sender == null
                || sender.hasPermission("plugmanreloaded.update.source")
                || sender.hasPermission("plugmanreloaded.admin");
        if (argLength == 3 && maySetSource) {
            list.add("source");
        }
        return list;
    }

    private boolean isFlagUsed(String flag, Set<String> usedTokens) {
        return isFlagUsed(null, flag, usedTokens);
    }

    private boolean isFlagUsed(@Nullable String subCommand, String flag, Set<String> usedTokens) {
        String lower = flag.toLowerCase(Locale.ROOT);
        if (usedTokens.contains(lower)) {
            return true;
        }
        CommandFlag cmdFlag = CommandFlags.findFlag(subCommand, lower);
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

    private List<String> subCommandNames(CommandSender sender) {
        if (commandsHandler == null) return Collections.emptyList();
        Set<String> allowed = new LinkedHashSet<>();
        for (SubCommand sub : commandsHandler.getMainSubCommands()) {
            if (sub.getPermission() == null || sub.getPermission().isBlank() || sender == null || sender.hasPermission(sub.getPermission()) || sender.hasPermission("plugmanreloaded.admin")) {
                allowed.add(sub.getName());
            }
        }
        return new ArrayList<>(allowed);
    }

    private List<String> allDeletablePlugins(@Nullable LifecycleManager lifecycle) {
        if (Bukkit.getServer() == null || lifecycle == null || plugin == null) return Collections.emptyList();
        Set<String> set = new LinkedHashSet<>();
        boolean useJar = plugin.getConfigManager().isUseJarFileNames();
        if (useJar) {
            for (PluginJarIndex.JarInfo info : lifecycle.getJarIndex().getEntries()) {
                if (info.declaredName() != null && lifecycle.isProtected(info.declaredName())) continue;
                set.add(info.file().getName());
            }
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (lifecycle.isProtected(p)) continue;
                File f = lifecycle.getPluginFile(p);
                if (f != null) {
                    set.add(f.getName());
                }
            }
        } else {
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (!lifecycle.isProtected(p)) {
                    set.add(p.getName());
                }
            }
            for (String name : lifecycle.getLoadableNames(false)) {
                if (!lifecycle.isProtected(name)) {
                    set.add(name);
                }
            }
        }
        List<String> list = new ArrayList<>(set);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    private List<String> allInspectablePlugins(@Nullable LifecycleManager lifecycle) {
        if (Bukkit.getServer() == null || lifecycle == null) return Collections.emptyList();
        Set<String> set = new LinkedHashSet<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            set.add(p.getName());
        }
        for (String name : lifecycle.getLoadableNames(false)) {
            set.add(name);
        }
        List<String> list = new ArrayList<>(set);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    private List<String> loadedPlugins(Predicate<Plugin> filter) {
        if (Bukkit.getServer() == null || plugin == null) return Collections.emptyList();
        Set<String> names = new LinkedHashSet<>();
        LifecycleManager lifecycle = plugin.getPluginLifecycleManager();
        boolean useJar = plugin.getConfigManager().isUseJarFileNames();

        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (filter.test(p)) {
                if (useJar) {
                    File f = lifecycle != null ? lifecycle.getPluginFile(p) : null;
                    if (f != null) {
                        names.add(f.getName());
                    } else {
                        names.add(p.getName());
                    }
                } else {
                    names.add(p.getName());
                }
            }
        }
        List<String> result = new ArrayList<>(names);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private List<String> downloadSuggestions() {
        if (plugin == null) return Collections.emptyList();
        MainConfig mainConfig = plugin.getConfigManager().getMainConfig();
        if (!mainConfig.isDownloadSuggestionsEnabled()) {
            return Collections.emptyList();
        }

        List<String> popular = mainConfig.getDownloadSuggestedPlugins();
        if (popular.isEmpty() || !mainConfig.isDownloadSuggestionsHideDownloaded()) {
            return popular;
        }

        LifecycleManager lifecycle = plugin.getPluginLifecycleManager();
        Set<String> presentKeys = new HashSet<>();
        if (Bukkit.getServer() != null) {
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                presentKeys.add(p.getName().toLowerCase(Locale.ROOT));
            }
        }
        for (PluginJarIndex.JarInfo entry : lifecycle.getJarIndex().getEntries()) {
            if (entry.declaredName() != null) {
                presentKeys.add(entry.declaredName().toLowerCase(Locale.ROOT));
            }
            String base = entry.file().getName().toLowerCase(Locale.ROOT);
            if (base.endsWith(".jar")) {
                base = base.substring(0, base.length() - 4);
            }
            presentKeys.add(base);
            int dash = base.indexOf('-');
            if (dash > 0) {
                presentKeys.add(base.substring(0, dash));
            }
        }

        List<String> result = new ArrayList<>(popular.size());
        for (String name : popular) {
            if (name == null || name.isBlank()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!presentKeys.contains(lower)) {
                result.add(name);
            }
        }
        return result;
    }

    private List<String> match(String token, List<String> candidates) {
        if (candidates.isEmpty()) return Collections.emptyList();
        return StringUtil.copyPartialMatches(token, candidates, new ArrayList<>(candidates.size()));
    }
}

