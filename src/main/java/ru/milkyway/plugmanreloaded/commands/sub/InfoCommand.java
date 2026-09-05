package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginInfo;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InfoCommand extends AbstractSubCommand {

    public InfoCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "info";
    }

    @Override
    public List<String> getAliases() {
        return List.of("lookup");
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.info";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (ctx.argCount() < 2 || !ctx.hasTarget()) {
            sendAction(sender, "help.info");
            return true;
        }

        String targetName = ctx.target();
        Plugin targetPlugin = plugin.getPluginLifecycleManager().getPlugin(targetName);
        PluginInfo info = null;
        boolean isUnloaded = false;

        if (targetPlugin != null) {
            info = plugin.getPluginLifecycleManager().getPluginInfo(targetPlugin);
        } else {
            File jar = plugin.getPluginLifecycleManager().findJarFile(targetName);
            if (jar != null && jar.exists()) {
                info = PluginInfo.fromJarFile(jar);
                isUnloaded = true;
            }
        }

        if (info == null) {
            sendAction(sender, "errors.plugin-not-found", Map.of("plugin", targetName));
            return true;
        }

        FileConfiguration config = plugin.getConfigManager().getMessagesConfig();

        String status;
        if (isUnloaded) {
            if (info.hasBootstrapper()) {
                status = plugin.getConfigManager().text("actions.info.status-unloaded-bootstrapper");
            } else {
                status = plugin.getConfigManager().text("actions.info.status-unloaded");
            }
        } else if (info.enabled()) {
            status = plugin.getConfigManager().text("actions.info.status-enabled");
        } else {
            status = plugin.getConfigManager().text("actions.info.status-disabled");
        }

        String format = info.paperPlugin()
                ? plugin.getConfigManager().text("actions.info.format-paper")
                : plugin.getConfigManager().text("actions.info.format-spigot");

        String noneAuthors = plugin.getConfigManager().text("actions.info.none-authors");
        String noneDesc = plugin.getConfigManager().text("actions.info.none-description");
        String noneWebsite = plugin.getConfigManager().text("actions.info.none-website");
        String noneDepends = plugin.getConfigManager().text("actions.info.none-depends");
        String noneSoftDepends = plugin.getConfigManager().text("actions.info.none-soft-depends");
        String noneMain = plugin.getConfigManager().text("actions.info.none-main");
        String noneFile = plugin.getConfigManager().text("actions.info.none-file");
        String noneSize = plugin.getConfigManager().text("actions.info.none-size");

        String authorsStr = info.authors().isEmpty() ? noneAuthors : String.join(", ", info.authors());
        String desc = info.description() != null && !info.description().isBlank() ? info.description() : noneDesc;
        String website = info.website() != null && !info.website().isBlank() ? info.website() : noneWebsite;
        String depends = info.depends().isEmpty() ? noneDepends : String.join(", ", info.depends());
        String softDepends = info.softDepends().isEmpty() ? noneSoftDepends : String.join(", ", info.softDepends());
        String mainClass = info.mainClass() != null && !info.mainClass().isBlank() ? info.mainClass() : noneMain;
        String file = info.file() != null ? info.file().getName() : noneFile;
        String size = info.fileSizeFormatted() != null ? info.fileSizeFormatted() : noneSize;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("plugin", info.name());
        String rawVer = info.version();
        String cleanVer = (rawVer != null && !rawVer.isBlank()) ? rawVer.trim().replaceFirst("^[vV]+", "") : "Unknown";
        placeholders.put("version", cleanVer.isEmpty() ? rawVer : cleanVer);
        placeholders.put("status", status);
        placeholders.put("format", format);
        placeholders.put("authors", authorsStr);
        placeholders.put("main", mainClass);
        placeholders.put("file", file);
        placeholders.put("size", size);
        placeholders.put("website", website);
        placeholders.put("description", desc);
        placeholders.put("depends", depends);
        placeholders.put("soft-depends", softDepends);
        placeholders.put("is-enabled", String.valueOf(!isUnloaded && info.enabled()));
        placeholders.put("is-unloaded", String.valueOf(isUnloaded));

        String actionPath;
        if (isUnloaded) {
            if (info.hasBootstrapper() && config.contains("actions.info.format-unloaded-bootstrapper")) {
                actionPath = "info.format-unloaded-bootstrapper";
            } else if (config.contains("actions.info.format-unloaded")) {
                actionPath = "info.format-unloaded";
            } else {
                actionPath = "info.format";
            }
        } else if (!info.enabled() && config.contains("actions.info.format-disabled")) {
            actionPath = "info.format-disabled";
        } else {
            actionPath = "info.format";
        }

        sendAction(sender, actionPath, placeholders);
        return true;
    }
}

