package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.DependencyGraph;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.update.install.BackupStore;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DeleteCommand extends AbstractSubCommand {

    private static final long[] RETRY_DELAYS_TICKS = {40L, 80L, 160L};

    public DeleteCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "delete";
    }

    @Override
    public List<String> getAliases() {
        return List.of("del");
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.delete";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    private record DeleteTarget(@Nullable File jarFile, @Nullable Plugin loadedPlugin,
                                String pluginName, String version) {}

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.argCount() < 2) {
            sendAction(sender, "help.delete");
            return true;
        }

        if (ctx.isCancel()) {
            return handleCancel(ctx, "delete");
        }

        String targetName = ctx.target();
        if (targetName.isEmpty() || targetName.equalsIgnoreCase("help")) {
            sendAction(sender, "help.delete");
            return true;
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        DeleteTarget target = resolveTarget(sender, targetName, pluginsDir);
        if (target == null) {
            return true;
        }

        if (checkProtected(sender, target.pluginName()) || isPluginLocked(sender, target.pluginName(), "delete")) {
            return true;
        }

        boolean force = ctx.hasFlag("y") || ctx.hasFlag("yes") || ctx.hasFlag("f") || ctx.hasFlag("force");
        String token = ctx.token();
        if (token != null) {
            if (!plugin.getConfirmationManager().validateAndConsume(sender, "delete", target.pluginName(), token)) {
                sendAction(sender, "errors.confirm-expired");
                return true;
            }
            force = true;
        } else if (force) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "delete", target.pluginName());
        }

        Map<String, String> placeholders = describe(target);
        if (!force) {
            return promptConfirmation(sender, target, placeholders);
        }

        boolean deleteDataFolder = plugin.getConfigManager().isDeletePluginDataFolder()
                || ctx.hasFlag("d") || ctx.hasFlag("data");
        return performDelete(sender, target, placeholders, pluginsDir, deleteDataFolder);
    }

    private @Nullable DeleteTarget resolveTarget(CommandSender sender, String targetName, @Nullable File pluginsDir) {
        File jarFile = findFileByName(targetName, pluginsDir);
        File remembered = resolveRememberedTarget(sender, targetName, pluginsDir);
        if (remembered != null) {
            jarFile = remembered;
        }
        boolean exactFile = jarFile != null;

        if (!exactFile) {
            List<PluginJarIndex.JarInfo> matches =
                    plugin.getPluginLifecycleManager().getJarIndex().findAll(targetName);
            if (matches.size() > 1) {
                reportAmbiguous(sender, targetName, matches);
                return null;
            }
            if (matches.size() == 1) {
                jarFile = matches.get(0).file();
            }
        }

        if (jarFile == null || !jarFile.isFile()) {
            jarFile = plugin.getPluginLifecycleManager().getJarIndex().find(targetName);
        }

        Plugin loadedPlugin = findLoadedByFile(jarFile);
        if (loadedPlugin == null) {
            Plugin byName = plugin.getPluginLifecycleManager().getPlugin(targetName);
            if (byName != null) {
                loadedPlugin = byName;
                File actual = plugin.getPluginLifecycleManager().getPluginFile(byName);
                if (!exactFile && actual != null && actual.isFile()) {
                    jarFile = actual;
                } else if (jarFile == null) {
                    jarFile = actual;
                }
            }
        }

        if (loadedPlugin == null && (jarFile == null || !jarFile.isFile())) {
            sendAction(sender, "errors.plugin-not-found", Map.of("plugin", targetName));
            return null;
        }

        return describeTarget(jarFile, loadedPlugin, targetName);
    }

    private @Nullable File findFileByName(String targetName, @Nullable File pluginsDir) {
        if (pluginsDir == null || !pluginsDir.isDirectory()) {
            return null;
        }

        File direct = new File(pluginsDir, targetName);
        if (direct.isFile() && isInsidePluginsDir(pluginsDir, direct)) {
            return direct;
        }
        if (targetName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return null;
        }

        File withExtension = new File(pluginsDir, targetName + ".jar");
        return withExtension.isFile() && isInsidePluginsDir(pluginsDir, withExtension) ? withExtension : null;
    }

    private @Nullable Plugin findLoadedByFile(@Nullable File jarFile) {
        if (jarFile == null) {
            return null;
        }
        for (Plugin candidate : plugin.getServer().getPluginManager().getPlugins()) {
            File candidateFile = plugin.getPluginLifecycleManager().getPluginFile(candidate);
            if (candidateFile != null && candidateFile.getAbsoluteFile().equals(jarFile.getAbsoluteFile())) {
                return candidate;
            }
        }
        return null;
    }

    private void reportAmbiguous(CommandSender sender, String targetName, List<PluginJarIndex.JarInfo> matches) {
        StringBuilder names = new StringBuilder();
        for (PluginJarIndex.JarInfo info : matches) {
            if (names.length() > 0) names.append("&f, &#FFFF00");
            names.append(info.file().getName());
        }
        sendAction(sender, "delete.ambiguous", Map.of(
                "plugin", targetName,
                "count", String.valueOf(matches.size()),
                "files", names.toString()));
    }

    private DeleteTarget describeTarget(@Nullable File jarFile, @Nullable Plugin loadedPlugin, String targetName) {
        if (loadedPlugin != null) {
            return new DeleteTarget(jarFile, loadedPlugin, loadedPlugin.getName(),
                    PluginMetaHelper.getVersion(loadedPlugin));
        }
        if (jarFile == null) {
            return new DeleteTarget(null, null, targetName, "1.0");
        }

        PluginJarIndex.JarDescriptor desc = plugin.getPluginLifecycleManager().getJarIndex().readDescriptor(jarFile);
        if (desc != null && desc.declaredName() != null && !desc.declaredName().isBlank()) {
            String version = desc.version() != null && !desc.version().isBlank()
                    ? PluginMetaHelper.cleanVersion(desc.version())
                    : "1.0";
            return new DeleteTarget(jarFile, null, desc.declaredName(), version);
        }

        String fileName = jarFile.getName();
        String withoutExtension = fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        return new DeleteTarget(jarFile, null, withoutExtension, "1.0");
    }

    private Map<String, String> describe(DeleteTarget target) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("plugin", target.pluginName());
        if (target.loadedPlugin() != null) {
            placeholders.putAll(getPluginPlaceholders(target.loadedPlugin()));
        } else {
            placeholders.put("version", target.version());
        }

        File jarFile = target.jarFile();
        placeholders.put("file", jarFile != null ? jarFile.getName() : target.pluginName() + ".jar");
        String noneSize = plugin.getConfigManager().text("actions.info.none-size");
        placeholders.put("size", jarFile != null && jarFile.exists()
                ? PluginMetaHelper.formatFileSize(jarFile.length())
                : noneSize);
        placeholders.put("cmd-type", "delete");
        return placeholders;
    }

    private boolean promptConfirmation(CommandSender sender, DeleteTarget target, Map<String, String> placeholders) {
        Set<String> dependents = target.loadedPlugin() != null
                ? plugin.getPluginLifecycleManager().getSafetyAdvisor().assess(target.loadedPlugin()).dependents()
                : Set.of();
        dependents = DependencyGraph.resolveDependentsWithFallback(dependents,
                plugin.getPluginLifecycleManager().getDependencyGraph(), target.pluginName());

        String confirmKey = "delete.confirm";
        if (!dependents.isEmpty()) {
            placeholders.put("dependents", String.join(", ", dependents));
            placeholders.put("dependents-count", String.valueOf(dependents.size()));
            confirmKey = "delete.confirm-dependents";
        }

        String sessionToken = plugin.getConfirmationManager().createSession(sender, "delete", target.pluginName(),
                target.jarFile() != null ? target.jarFile().getAbsolutePath() : null);
        placeholders.put("token", sessionToken);
        sendAction(sender, confirmKey, placeholders);
        return true;
    }

    private boolean performDelete(CommandSender sender, DeleteTarget target, Map<String, String> placeholders,
                                  @Nullable File pluginsDir, boolean deleteDataFolder) {
        File jarFile = target.jarFile();
        if (jarFile == null || !jarFile.isFile()) {
            sendAction(sender, "delete.file-not-found", placeholders);
            return true;
        }

        File dataFolder = target.loadedPlugin() != null ? target.loadedPlugin().getDataFolder() : null;
        backupBeforeDelete(target, placeholders, pluginsDir, deleteDataFolder, dataFolder);

        sendAction(sender, "delete.start", placeholders);
        long start = System.currentTimeMillis();

        if (target.loadedPlugin() != null) {
            PluginResult unloadResult = plugin.getPluginLifecycleManager().unload(target.loadedPlugin());
            if (!unloadResult.success()) {
                sendAction(sender, unloadResult.messageKey(), unloadResult.placeholders());
                return true;
            }
        }

        plugin.getHotSwapManager().temporarilyIgnore(jarFile.getName(), 5000L);

        boolean deleted = false;
        try {
            deleted = Files.deleteIfExists(jarFile.toPath());
        } catch (Throwable t) {
            Log.debug("deletecommand.file-delete-error", t, "file", jarFile.getName());
        }

        if (!deleted) {
            sendAction(sender, "delete.pending-file", placeholders);
            TaskScheduler.runSyncLater(plugin, () -> retryDelete(sender, jarFile, placeholders,
                    deleteDataFolder, target.pluginName(), dataFolder, 1), RETRY_DELAYS_TICKS[0]);
            return true;
        }

        if (deleteDataFolder) {
            deleteDataFolderSafely(pluginsDir, target.pluginName(), dataFolder);
        }

        plugin.getPluginLifecycleManager().getJarIndex().invalidate();
        plugin.getPluginLifecycleManager().getBrigadierManager().syncCommands();

        placeholders.put("time", String.valueOf(System.currentTimeMillis() - start));
        sendAction(sender, "delete.success", placeholders);
        return true;
    }

    private void backupBeforeDelete(DeleteTarget target, Map<String, String> placeholders, @Nullable File pluginsDir,
                                    boolean deleteDataFolder, @Nullable File dataFolder) {
        BackupStore backups = null;
        try {
            backups = new BackupStore(pluginsDir,
                    plugin.getConfigManager().getBackupKeepDays(),
                    plugin.getConfigManager().getBackupMaxPerPlugin());
            backups.backup(target.pluginName(), placeholders.getOrDefault("version", "1.0"), target.jarFile());
        } catch (Throwable t) {
            Log.warn("deletecommand.backup-failed", t, "plugin", target.pluginName());
        }

        if (!deleteDataFolder || backups == null) {
            return;
        }

        File targetDataFolder = dataFolder != null ? dataFolder : new File(pluginsDir, target.pluginName());
        if (!targetDataFolder.isDirectory()) {
            return;
        }
        try {
            backups.backupFolder(target.pluginName(), targetDataFolder);
        } catch (Throwable t) {
            Log.warn("deletecommand.folder-backup-failed", t, "plugin", target.pluginName());
        }
    }

    private @Nullable File resolveRememberedTarget(CommandSender sender, String targetName, @Nullable File pluginsDir) {
        String remembered = plugin.getConfirmationManager().peekPayload(sender, "delete", targetName);
        if (remembered == null || remembered.isBlank()) {
            return null;
        }
        File file = new File(remembered);
        if (!file.isFile()) {
            return null;
        }
        if (pluginsDir != null && !isInsidePluginsDir(pluginsDir, file)) {
            return null;
        }
        return file;
    }

    private boolean isInsidePluginsDir(File pluginsDir, File file) {
        try {
            String pluginsPath = pluginsDir.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(pluginsPath);
        } catch (IOException e) {
            Log.debug("deletecommand.canonical-path-check-failed", e, "file", file.getName());
            return false;
        }
    }

    private void retryDelete(CommandSender sender, File jarFile, Map<String, String> placeholders,
                             boolean deleteDataFolder, String pluginName, @Nullable File dataFolder, int attempt) {
        boolean deleted = false;
        try {
            deleted = Files.deleteIfExists(jarFile.toPath());
        } catch (Throwable t) {
            Log.debug("deletecommand.delete-attempt-failed", t, "attempt", String.valueOf(attempt), "file", jarFile.getName());
        }

        if (!deleted) {
            if (attempt < RETRY_DELAYS_TICKS.length) {
                TaskScheduler.runSyncLater(plugin, () -> retryDelete(sender, jarFile, placeholders,
                        deleteDataFolder, pluginName, dataFolder, attempt + 1), RETRY_DELAYS_TICKS[attempt]);
                return;
            }
            Map<String, String> failed = new HashMap<>(placeholders);
            failed.put("attempts", String.valueOf(RETRY_DELAYS_TICKS.length + 1));
            sendAction(sender, "delete.failed-file", failed);
            Log.warn("deletecommand.file-locked", "file", jarFile.getName());
            return;
        }

        if (deleteDataFolder) {
            deleteDataFolderSafely(plugin.getDataFolder().getParentFile(), pluginName, dataFolder);
        }

        plugin.getPluginLifecycleManager().getJarIndex().invalidate();
        plugin.getPluginLifecycleManager().getBrigadierManager().syncCommands();

        sendAction(sender, "delete.success", placeholders);
    }

    private void deleteDataFolderSafely(@Nullable File pluginsDir, String realPluginName, @Nullable File loadedDataFolder) {
        if (pluginsDir == null || !pluginsDir.isDirectory() || realPluginName == null || realPluginName.isBlank()) {
            return;
        }

        File targetFolder = loadedDataFolder != null ? loadedDataFolder : new File(pluginsDir, realPluginName);
        if (!targetFolder.exists() || !targetFolder.isDirectory()) {
            return;
        }

        try {
            Path pluginsPath = pluginsDir.toPath().toRealPath();
            Path folderPath = targetFolder.toPath().toRealPath();

            if (folderPath.equals(pluginsPath)) {
                return;
            }

            if (!folderPath.startsWith(pluginsPath)) {
                return;
            }

            Files.walkFileTree(folderPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable t) {
            Log.warn("deletecommand.folder-delete-error", t, "plugin", realPluginName);
        }
    }

}

