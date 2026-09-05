package ru.milkyway.plugmanreloaded.commands.sub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ListCommand extends AbstractSubCommand {

    private static final int MAX_AUTHORS_LENGTH = 96;
    private static final int MAX_CHUNK_LENGTH = 8000;

    public enum PluginState {
        ENABLED,
        DISABLED,
        UNLOADED
    }

    private record PluginItem(String name, String lookupName, String version, String authors, PluginState state) {}

    private record ListEntry(Component component, int sourceLength) {}

    public ListCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "list";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.list";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        boolean showVersions = ctx.hasFlag("v") || ctx.hasFlag("versions");
        boolean showJars = ctx.hasFlag("j") || ctx.hasFlag("jar");

        List<PluginItem> allPlugins = collectAllPlugins(showJars);
        if (allPlugins.isEmpty()) {
            sendAction(sender, "list.empty");
            return true;
        }

        allPlugins.sort(Comparator.comparing(PluginItem::name, String.CASE_INSENSITIVE_ORDER));

        int enabledCount = 0;
        int disabledCount = 0;
        int unloadedCount = 0;

        for (PluginItem p : allPlugins) {
            switch (p.state()) {
                case ENABLED -> enabledCount++;
                case DISABLED -> disabledCount++;
                case UNLOADED -> unloadedCount++;
            }
        }

        FileConfiguration config = plugin.getConfigManager().getMessagesConfig();
        String separator = plugin.getConfigManager().text("actions.list.separator");

        Map<String, String> placeholders = Map.of(
                "total", String.valueOf(allPlugins.size()),
                "enabled", String.valueOf(enabledCount),
                "disabled", String.valueOf(disabledCount + unloadedCount),
                "disabled-only", String.valueOf(disabledCount),
                "unloaded", String.valueOf(unloadedCount)
        );

        List<ListEntry> entries = buildEntries(allPlugins, config, showVersions);

        List<String> formatList = config.getStringList("actions.list.format");
        if (formatList.isEmpty()) {
            sendEntries(sender, entries, separator, Component.text("  "));
            return true;
        }

        for (String raw : formatList) {
            if (raw.contains("{plugins}")) {
                int idx = raw.indexOf("{plugins}");
                String prefix = raw.substring(0, idx).replaceAll("(?i)^\\[message\\]\\s*", "");
                Component prefixComp = prefix.isEmpty() ? Component.empty() : HexColors.translateToComponent(prefix);
                sendEntries(sender, entries, separator, prefixComp);
            } else {
                plugin.getConfigManager().getActionManager().executeRawAction(sender, raw, placeholders);
            }
        }
        return true;
    }

    private List<PluginItem> collectAllPlugins(boolean showJars) {
        List<PluginItem> list = new ArrayList<>();

        if (showJars) {
            Map<String, Plugin> loadedByFile = new HashMap<>();
            Set<Plugin> handledPlugins = new HashSet<>();

            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                File f = plugin.getPluginLifecycleManager().getPluginFile(p);
                if (f != null) {
                    loadedByFile.put(f.getName().toLowerCase(Locale.ROOT), p);
                }
            }

            try {
                List<PluginJarIndex.JarInfo> entries = plugin.getPluginLifecycleManager().getJarIndex().getEntries();
                for (PluginJarIndex.JarInfo info : entries) {
                    String fn = info.file().getName();
                    Plugin loaded = loadedByFile.get(fn.toLowerCase(Locale.ROOT));
                    if (loaded != null) {
                        handledPlugins.add(loaded);
                        PluginState state = loaded.isEnabled() ? PluginState.ENABLED : PluginState.DISABLED;
                        list.add(new PluginItem(fn, loaded.getName(), resolveVersion(loaded), resolveAuthors(loaded), state));
                    } else {
                        list.add(new PluginItem(fn, info.preferredName(), info.version(), resolveAuthors(info.authors()), PluginState.UNLOADED));
                    }
                }
            } catch (Throwable t) {
                Log.debug("listcommand.jar-list-failed", t);
            }

            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (!handledPlugins.contains(p)) {
                    File f = plugin.getPluginLifecycleManager().getPluginFile(p);
                    String fn = f != null ? f.getName() : p.getName() + ".jar";
                    PluginState state = p.isEnabled() ? PluginState.ENABLED : PluginState.DISABLED;
                    list.add(new PluginItem(fn, p.getName(), resolveVersion(p), resolveAuthors(p), state));
                }
            }
        } else {
            Set<String> processedNames = new HashSet<>();

            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                processedNames.add(p.getName().toLowerCase(Locale.ROOT));
                PluginState state = p.isEnabled() ? PluginState.ENABLED : PluginState.DISABLED;
                list.add(new PluginItem(p.getName(), p.getName(), resolveVersion(p), resolveAuthors(p), state));
            }

            try {
                List<PluginJarIndex.JarInfo> unloaded = plugin.getPluginLifecycleManager().getJarIndex().getUnloadedJars();
                for (PluginJarIndex.JarInfo info : unloaded) {
                    String key = info.declaredName() != null ? info.declaredName().toLowerCase(Locale.ROOT) : info.file().getName().toLowerCase(Locale.ROOT);
                    if (processedNames.add(key)) {
                        list.add(new PluginItem(info.preferredName(), info.preferredName(), info.version(), resolveAuthors(info.authors()), PluginState.UNLOADED));
                    }
                }
            } catch (Throwable t) {
                Log.debug("listcommand.unloaded-jar-list-failed", t);
            }
        }

        return list;
    }

    private List<ListEntry> buildEntries(List<PluginItem> items, FileConfiguration config, boolean showVersions) {
        String enabledTextTemplate = plugin.getConfigManager().text("actions.list.plugin-enabled.text");
        String disabledTextTemplate = plugin.getConfigManager().text("actions.list.plugin-disabled.text");
        String unloadedTextTemplate = config.getString("actions.list.plugin-unloaded.text", disabledTextTemplate);

        String enabledHoverTemplate = extractHoverTemplate(config, "actions.list.plugin-enabled.hover");
        String disabledHoverTemplate = extractHoverTemplate(config, "actions.list.plugin-disabled.hover");
        String unloadedHoverTemplate = extractHoverTemplate(config, "actions.list.plugin-unloaded.hover");
        if (unloadedHoverTemplate.isEmpty()) {
            unloadedHoverTemplate = disabledHoverTemplate;
        }

        List<ListEntry> entries = new ArrayList<>(items.size());

        for (PluginItem p : items) {
            String name = HexColors.escapeTags(p.name());
            String lookupName = HexColors.escapeTags(p.lookupName());
            String version = HexColors.escapeTags(p.version());
            String authors = HexColors.escapeTags(p.authors());

            String textTemplate;
            String hoverTemplate;

            if (p.state() == PluginState.ENABLED) {
                textTemplate = enabledTextTemplate;
                hoverTemplate = enabledHoverTemplate;
            } else if (p.state() == PluginState.UNLOADED) {
                textTemplate = unloadedTextTemplate;
                hoverTemplate = unloadedHoverTemplate;
            } else {
                textTemplate = disabledTextTemplate;
                hoverTemplate = disabledHoverTemplate;
            }

            String text = textTemplate
                    .replace("{plugin}", name)
                    .replace("{version}", version)
                    .replace("{authors}", authors);

            if (showVersions) {
                String colorPrefix = extractColorPrefix(textTemplate);
                String formattedVersion = (version.startsWith("v") || version.startsWith("V"))
                        ? version
                        : "v" + (version.isEmpty() ? "1.0" : version);
                text = text + " &f(" + colorPrefix + formattedVersion + "&f)";
            }

            Component entry = HexColors.translateToComponent(text);
            int sourceLength = text.length();

            if (!hoverTemplate.isEmpty()) {
                String hover = hoverTemplate
                        .replace("{plugin}", name)
                        .replace("{version}", version)
                        .replace("{authors}", authors);
                entry = entry.hoverEvent(HoverEvent.showText(HexColors.translateToComponent(hover)));
                sourceLength += hover.length();
            }

            entry = entry.clickEvent(ClickEvent.runCommand("/plm info " + lookupName));
            entries.add(new ListEntry(entry, sourceLength));
        }
        return entries;
    }

    private String extractColorPrefix(@Nullable String template) {
        if (template == null) return "";
        int idx = template.indexOf("{plugin}");
        if (idx > 0) {
            return template.substring(0, idx);
        }
        return "";
    }

    private String extractHoverTemplate(FileConfiguration config, String path) {
        if (config.isList(path)) {
            return String.join("\n", config.getStringList(path));
        }
        return config.getString(path, "");
    }

    private String resolveVersion(Plugin p) {
        return PluginMetaHelper.getVersion(p);
    }

    private String resolveAuthors(Plugin p) {
        List<String> authorsList = PluginMetaHelper.getAuthors(p);
        if (authorsList != null && !authorsList.isEmpty()) {
            return trim(String.join(", ", authorsList));
        }
        return resolveAuthors("");
    }

    private String resolveAuthors(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return plugin.getConfigManager().text("actions.info.none-authors");
        }
        return trim(raw);
    }

    private void sendEntries(CommandSender sender, List<ListEntry> entries, String separator, Component prefixComp) {
        Component separatorComp = HexColors.translateToComponent(separator);
        Component currentChunk = prefixComp;
        int lengthInChunk = 0;
        int countInChunk = 0;

        for (ListEntry entry : entries) {
            int entryLength = entry.sourceLength() + (countInChunk > 0 ? separator.length() : 0);
            if (countInChunk > 0 && lengthInChunk + entryLength > MAX_CHUNK_LENGTH) {
                sender.sendMessage(currentChunk);
                currentChunk = prefixComp;
                lengthInChunk = 0;
                countInChunk = 0;
                entryLength = entry.sourceLength();
            }
            if (countInChunk > 0) {
                currentChunk = currentChunk.append(separatorComp);
            }
            currentChunk = currentChunk.append(entry.component());
            lengthInChunk += entryLength;
            countInChunk++;
        }

        if (countInChunk > 0) {
            sender.sendMessage(currentChunk);
        }
    }

    private String trim(String value) {
        return value.length() <= MAX_AUTHORS_LENGTH ? value : value.substring(0, MAX_AUTHORS_LENGTH - 1) + "…";
    }
}

