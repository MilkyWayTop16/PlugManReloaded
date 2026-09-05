package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoadCommand extends AbstractSubCommand {

    public LoadCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "load";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.load";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.argCount() < 2) {
            sendAction(sender, "help.load");
            return true;
        }

        if (ctx.isAll()) {
            List<PluginJarIndex.JarInfo> unloaded =
                    plugin.getPluginLifecycleManager().getJarIndex().getUnloadedJars();
            if (unloaded.isEmpty()) {
                sendAction(sender, "load.all-empty");
                return true;
            }
            sendAction(sender, "load.all-start");
            BulkOperationResult res = plugin.getPluginLifecycleManager().bulkLoadJars(unloaded);
            sendBulkReport(sender, "load", res);
            return true;
        }

        String jarName = ctx.target();
        File file = plugin.getPluginLifecycleManager().findJarFile(jarName);
        if (file == null || !file.exists()) {
            sendAction(sender, "errors.file-not-found", Map.of(
                    "plugin", jarName,
                    "file", jarName
            ));
            return true;
        }

        String rawPluginName = file.getName().replaceAll("(?i)\\.jar$", "");
        sendAction(sender, "load.start", Map.of(
                "file", file.getName(),
                "plugin", rawPluginName
        ));
        long start = System.currentTimeMillis();

        PluginResult result = plugin.getPluginLifecycleManager().load(file);
        long elapsed = System.currentTimeMillis() - start;

        Map<String, String> placeholders = new HashMap<>(result.placeholders());
        placeholders.put("file", file.getName());
        placeholders.put("time", String.valueOf(elapsed));

        String loadedName = result.placeholders().get("plugin");
        if (loadedName != null) {
            Plugin newlyLoaded = plugin.getPluginLifecycleManager().getPlugin(loadedName);
            if (newlyLoaded != null) {
                placeholders.putAll(getPluginPlaceholders(newlyLoaded));
            }
        }

        sendAction(sender, result.messageKey(), placeholders);
        return true;
    }
}

