package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class DumpCommand extends AbstractSubCommand {

    public DumpCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "dump";
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.dump";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        sendAction(sender, "dump.start");

        File dumpsDir = new File(plugin.getDataFolder(), "dumps");
        if (!dumpsDir.exists()) {
            dumpsDir.mkdirs();
        }

        LocalDateTime now = LocalDateTime.now();
        String fileTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String readableDate = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        File dumpFile = new File(dumpsDir, "dump-" + fileTime + ".txt");

        try (PrintWriter writer = new PrintWriter(new FileWriter(dumpFile, StandardCharsets.UTF_8))) {
            writer.println("================================================================================");
            writer.println("                        PlugManReloaded Server Environment Dump                  ");
            writer.println("                           (By MilkyWay for everyone)                           ");
            writer.println("================================================================================");
            writer.println("Created: " + readableDate);
            writer.println("Server core: " + Bukkit.getName() + " (" + Bukkit.getVersion() + ")");
            writer.println("Bukkit API version: " + Bukkit.getBukkitVersion());
            writer.println("Platform: " + PlatformDetector.getPlatformName());
            writer.println("Java version: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            writer.println("Operating system: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            writer.println("Allocated memory (Total RAM): " + (Runtime.getRuntime().totalMemory() / (1024 * 1024)) + " MB");
            writer.println("Free memory (Free RAM): " + (Runtime.getRuntime().freeMemory() / (1024 * 1024)) + " MB");
            writer.println("Max memory (Max RAM): " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
            writer.println("================================================================================");
            writer.println("List of installed plugins (total: " + Bukkit.getPluginManager().getPlugins().length + "):");
            writer.println("--------------------------------------------------------------------------------");

            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                String status = p.isEnabled() ? "[ENABLED] " : "[DISABLED]";
                String version = PluginMetaHelper.getVersion(p);
                String authors = p.getDescription().getAuthors().isEmpty()
                        ? "Not specified"
                        : String.join(", ", p.getDescription().getAuthors());
                String main = p.getDescription().getMain();
                writer.println(String.format("%-11s %-25s v%-12s (Authors: %s | Main class: %s)", status, p.getName(), version, authors, main));
            }

            writer.println("================================================================================");
            writer.flush();

            Log.info("dumpcommand.saved", "file", dumpFile.getName());
            sendAction(sender, "dump.success", Map.of("file", dumpFile.getName()));
        } catch (Exception e) {
            Log.error("dumpcommand.failed", "error", e.getMessage());
            sendAction(sender, "dump.failed", Map.of("error", e.getMessage() != null ? e.getMessage() : plugin.getConfigManager().text("actions.errors.details.unknown")));
        }

        return true;
    }
}

