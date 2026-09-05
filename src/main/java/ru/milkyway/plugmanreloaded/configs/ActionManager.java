package ru.milkyway.plugmanreloaded.configs;

import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ActionManager {

    private final PlugManReloaded plugin;
    private final ConfigManager configManager;
    private final Map<String, List<ParsedAction>> actionsCache = new HashMap<>();

    private static final Pattern ACTION_PATTERN = Pattern.compile("^\\[([a-zA-Z0-9_-]+)\\]\\s?(.*)$");

    public record ParsedAction(String type, String content) {}

    public ActionManager(PlugManReloaded plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void loadActions(FileConfiguration config) {
        loadActions(config, resolveLanguage());
    }

    public void loadActions(FileConfiguration config, @Nullable String language) {
        actionsCache.clear();

        String lang = language != null && !language.isBlank() ? language.trim().toLowerCase(Locale.ROOT) : resolveLanguage();

        FileConfiguration ruDefault = ConfigUpdater.getDefaultConfig(plugin, "messages/ru-messages.yml");
        FileConfiguration enDefault = ConfigUpdater.getDefaultConfig(plugin, "messages/en-messages.yml");

        FileConfiguration langDefault = null;
        if (!"ru".equals(lang) && !"en".equals(lang)) {
            langDefault = ConfigUpdater.getDefaultConfig(plugin, "messages/" + lang + "-messages.yml");
            if (langDefault == null) {
                langDefault = ConfigUpdater.getDefaultConfig(plugin, "messages/" + lang + ".yml");
            }
        }

        if (ruDefault != null) {
            populateActions(ruDefault);
        }

        if ("en".equals(lang)) {
            if (enDefault != null) {
                populateActions(enDefault);
            }
        } else if (!"ru".equals(lang)) {
            if (enDefault != null) {
                populateActions(enDefault);
            }
            if (langDefault != null) {
                populateActions(langDefault);
            }
        }

        if (config != null) {
            populateActions(config);
        }
    }

    private void populateActions(FileConfiguration configuration) {
        for (String key : configuration.getKeys(true)) {
            if (!key.startsWith("actions.")) {
                continue;
            }

            List<ParsedAction> parsed;
            if (configuration.isList(key)) {
                parsed = new ArrayList<>();
                for (String raw : configuration.getStringList(key)) {
                    parsed.add(parseLine(raw));
                }
            } else if (configuration.isString(key)) {
                parsed = List.of(parseLine(configuration.getString(key, "")));
            } else {
                continue;
            }

            cacheUnderBothKeys(key, parsed);
            if (key.endsWith(".format")) {
                cacheUnderBothKeys(key.substring(0, key.length() - ".format".length()), parsed);
            }
        }
    }

    private static ParsedAction parseLine(String raw) {
        Matcher matcher = ACTION_PATTERN.matcher(raw);
        return matcher.matches()
                ? new ParsedAction(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2))
                : new ParsedAction("message", raw);
    }

    private void cacheUnderBothKeys(String actionsKey, List<ParsedAction> parsed) {
        actionsCache.put(actionsKey, parsed);
        actionsCache.put(actionsKey.substring("actions.".length()), parsed);
    }

    private String resolveLanguage() {
        if (configManager != null && configManager.getMainConfig() != null) {
            String lang = configManager.getMainConfig().getLanguage();
            if (lang != null && !lang.isBlank()) {
                return lang.trim().toLowerCase(Locale.ROOT);
            }
        }
        return "ru";
    }

    private FileConfiguration loadDefaultConfig() {
        return loadDefaultConfig(resolveLanguage());
    }

    private FileConfiguration loadDefaultConfig(String lang) {
        if ("en".equals(lang)) {
            FileConfiguration en = ConfigUpdater.getDefaultConfig(plugin, "messages/en-messages.yml");
            if (en != null) return en;
        } else if (!"ru".equals(lang)) {
            FileConfiguration custom = ConfigUpdater.getDefaultConfig(plugin, "messages/" + lang + "-messages.yml");
            if (custom != null) return custom;
            FileConfiguration en = ConfigUpdater.getDefaultConfig(plugin, "messages/en-messages.yml");
            if (en != null) return en;
        }
        return ConfigUpdater.getDefaultConfig(plugin, "messages/ru-messages.yml");
    }

    public void executeActions(CommandSender sender, String actionKey) {
        executeActions(sender, actionKey, Collections.emptyMap());
    }

    public void executeActions(CommandSender sender, String actionKey, Map<String, String> placeholders) {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            TaskScheduler.runSync(plugin, () -> executeActionsInternal(sender, actionKey, placeholders));
        } else {
            executeActionsInternal(sender, actionKey, placeholders);
        }
    }

    public void executeRawAction(CommandSender sender, @Nullable String rawLine, Map<String, String> placeholders) {
        if (rawLine == null || rawLine.isEmpty()) return;
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            TaskScheduler.runSync(plugin, () -> executeRawActionInternal(sender, rawLine, placeholders));
        } else {
            executeRawActionInternal(sender, rawLine, placeholders);
        }
    }

    private void executeRawActionInternal(CommandSender sender, String rawLine, Map<String, String> placeholders) {
        Matcher matcher = ACTION_PATTERN.matcher(rawLine);
        ParsedAction action;
        if (matcher.matches()) {
            action = new ParsedAction(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        } else {
            action = new ParsedAction("message", rawLine);
        }
        String actionKey = placeholders != null ? placeholders.get("action-key") : null;
        executeParsedAction(sender, action, placeholders, actionKey);
    }

    private void executeActionsInternal(CommandSender sender, String actionKey, Map<String, String> placeholders) {
        List<ParsedAction> actions = actionsCache.get(actionKey);
        if (actions == null) {
            actions = actionsCache.get(actionKey + ".format");
        }
        if (actions == null) {
            if (actionKey.startsWith("actions.")) {
                String sub = actionKey.substring(8);
                actions = actionsCache.get(sub);
                if (actions == null) actions = actionsCache.get(sub + ".format");
            } else {
                actions = actionsCache.get("actions." + actionKey);
                if (actions == null) actions = actionsCache.get("actions." + actionKey + ".format");
            }
        }
        if (actions == null) {
            if (actionKey.contains("canceled")) {
                String alt = actionKey.replace("canceled", "cancelled");
                actions = actionsCache.get(alt);
                if (actions == null) actions = actionsCache.get("actions." + alt);
            } else if (actionKey.contains("cancelled")) {
                String alt = actionKey.replace("cancelled", "canceled");
                actions = actionsCache.get(alt);
                if (actions == null) actions = actionsCache.get("actions." + alt);
            }
        }
        if (actions == null || actions.isEmpty()) {
            return;
        }

        for (ParsedAction action : actions) {
            executeParsedAction(sender, action, placeholders, actionKey);
        }
    }

    private void executeParsedAction(CommandSender sender, ParsedAction action, Map<String, String> placeholders, String actionKey) {
        String text = applyPlaceholders(action.content(), placeholders);
        Player player = sender instanceof Player ? (Player) sender : null;

        if (text.trim().isEmpty() && !action.content().trim().isEmpty()
                && (action.content().contains("{") || action.content().contains("%"))) {
            return;
        }

        switch (action.type()) {
            case "message" -> {
                if (sender != null) {
                    sendMessage(sender, text, placeholders, actionKey);
                }
            }
            case "message-console" -> {
                if (Bukkit.getServer() != null) {
                    sendMessage(Bukkit.getConsoleSender(), text, placeholders, actionKey);
                }
            }
            case "actionbar" -> {
                if (player != null) {
                    sendActionBar(player, text);
                }
            }
            case "title" -> {
                if (player != null) {
                    sendTitle(player, text);
                }
            }
            case "sound" -> {
                if (player != null) {
                    playSound(player, text);
                }
            }
            case "broadcast" -> {
                if (Bukkit.getServer() != null) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        sendMessage(online, text, placeholders, actionKey);
                    }
                    sendMessage(Bukkit.getConsoleSender(), text, placeholders, actionKey);
                }
            }
            case "console-command", "command" -> {
                if (Bukkit.getServer() != null) {
                    Map<String, String> safePlaceholders = sanitizePlaceholders(placeholders);
                    String safeText = applyPlaceholders(action.content(), safePlaceholders);
                    if (PlatformDetector.isFolia()) {
                        TaskScheduler.runSync(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), safeText));
                    } else {
                        if (Bukkit.isPrimaryThread()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), safeText);
                        } else {
                            TaskScheduler.runSync(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), safeText));
                        }
                    }
                }
            }
            case "player-command" -> {
                if (player != null) {
                    Map<String, String> safePlaceholders = sanitizePlaceholders(placeholders);
                    String safeText = applyPlaceholders(action.content(), safePlaceholders);
                    TaskScheduler.runForEntity(plugin, player, () -> {
                        if (player.isOnline()) {
                            player.performCommand(safeText);
                        }
                    });
                }
            }
        }
    }

    private Map<String, String> sanitizePlaceholders(Map<String, String> placeholders) {
        if (placeholders == null) return null;
        java.util.Map<String, String> safe = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (entry.getValue() == null) continue;
            safe.put(entry.getKey(), entry.getValue().replaceAll("[^a-zA-Z0-9_.-]", ""));
        }
        return safe;
    }

    private void sendMessage(CommandSender recipient, @Nullable String text, Map<String, String> placeholders, String actionKey) {
        if (text == null || recipient == null) return;
        FileConfiguration config = configManager != null ? configManager.getMessagesConfig() : null;

        if (text.contains("\n")) {
            for (String subLine : text.split("\n", -1)) {
                if (ChatButtonFactory.hasButtonToken(subLine)) {
                    recipient.sendMessage(ChatButtonFactory.renderInteractiveLine(config, subLine, placeholders, actionKey));
                } else {
                    recipient.sendMessage(HexColors.translateToComponent(subLine));
                }
            }
            return;
        }

        if (ChatButtonFactory.hasButtonToken(text)) {
            recipient.sendMessage(ChatButtonFactory.renderInteractiveLine(config, text, placeholders, actionKey));
        } else {
            recipient.sendMessage(HexColors.translateToComponent(text));
        }
    }

    private void sendActionBar(Player player, String text) {
        if (player == null || !player.isOnline()) return;
        player.sendActionBar(HexColors.translateToComponent(text));
    }

    private void sendTitle(Player player, String text) {
        if (player == null || !player.isOnline()) return;
        String[] parts = text.split(";");
        String title = parts.length > 0 ? parts[0] : "";
        String subtitle = parts.length > 1 ? parts[1] : "";
        int fadeIn = parts.length > 2 ? parseInt(parts[2], 10) : 10;
        int stay = parts.length > 3 ? parseInt(parts[3], 70) : 70;
        int fadeOut = parts.length > 4 ? parseInt(parts[4], 20) : 20;

        try {
            Title.Times times = Title.Times.times(
                    Ticks.duration(fadeIn),
                    Ticks.duration(stay),
                    Ticks.duration(fadeOut)
            );
            player.showTitle(Title.title(
                    HexColors.translateToComponent(title),
                    HexColors.translateToComponent(subtitle),
                    times
            ));
        } catch (Throwable t1) {
            try {
                player.sendTitle(
                        HexColors.translate(title),
                        HexColors.translate(subtitle),
                        fadeIn,
                        stay,
                        fadeOut
                );
            } catch (Throwable t2) {
                Log.warn("actionmanager.title-error", "player", player.getName(), "error", t2.getMessage());
            }
        }
    }

    private void playSound(Player player, String text) {
        if (player == null || !player.isOnline()) return;
        String[] parts = text.trim().split("[;\\s]+");
        String soundName = parts[0].trim().toUpperCase(Locale.ROOT);
        float volume = parts.length > 1 ? parseFloat(parts[1].trim(), 1.0f) : 1.0f;
        float pitch = parts.length > 2 ? parseFloat(parts[2].trim(), 1.0f) : 1.0f;

        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
            return;
        } catch (IllegalArgumentException ignored) {
        } catch (Exception t) {
            Log.warn("actionmanager.sound-error", "sound", soundName, "error", t.getMessage());
            return;
        }

        try {
            player.playSound(player.getLocation(), soundName.toLowerCase(Locale.ROOT), volume, pitch);
        } catch (Exception t) {
            Log.warn("actionmanager.unknown-sound", "sound", soundName);
        }
    }

    private String applyPlaceholders(@Nullable String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null || placeholders.isEmpty()) return text;

        Map<String, String> effective = new HashMap<>(placeholders);
        populateAliasIfAbsent(effective, "author", "authors");
        populateAliasIfAbsent(effective, "authors", "author");
        populateAliasIfAbsent(effective, "deps", "dependencies");
        populateAliasIfAbsent(effective, "dependencies", "deps");
        populateAliasIfAbsent(effective, "depends", "dependencies");
        populateAliasIfAbsent(effective, "to", "latest");
        populateAliasIfAbsent(effective, "latest", "to");
        populateAliasIfAbsent(effective, "from", "current");
        populateAliasIfAbsent(effective, "current", "from");

        String result = text;
        for (Map.Entry<String, String> entry : effective.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue() != null ? entry.getValue() : "";
            if (PluginMetaHelper.isVersionKey(key)) {
                val = PluginMetaHelper.cleanVersion(val);
            }

            String token1 = "{" + key + "}";
            String token2 = "%" + key + "%";

            if (ChatButtonFactory.hasButtonToken(token1)) {
                continue;
            }

            if (val.contains("\n")) {
                result = replaceMultilineToken(result, token1, val);
                result = replaceMultilineToken(result, token2, val);
            } else {
                result = result.replace(token1, val);
                result = result.replace(token2, val);
            }
        }
        return PluginMetaHelper.cleanupDoubleV(result);
    }

    private void populateAliasIfAbsent(Map<String, String> map, String targetKey, String sourceKey) {
        if (!map.containsKey(targetKey) && map.containsKey(sourceKey)) {
            map.put(targetKey, map.get(sourceKey));
        }
    }

    private String replaceMultilineToken(String text, String token, String multilineVal) {
        int idx = text.indexOf(token);
        if (idx == -1) return text;

        StringBuilder sb = new StringBuilder();
        int lastIdx = 0;
        while (idx != -1) {
            sb.append(text, lastIdx, idx);
            String before = text.substring(lastIdx, idx);
            int lastNewline = before.lastIndexOf('\n');
            String linePrefix = (lastNewline >= 0) ? before.substring(lastNewline + 1) : before;

            String indentedVal = multilineVal.replace("\n", "\n" + linePrefix);
            sb.append(indentedVal);
            lastIdx = idx + token.length();
            idx = text.indexOf(token, lastIdx);
        }
        sb.append(text.substring(lastIdx));
        return sb.toString();
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

