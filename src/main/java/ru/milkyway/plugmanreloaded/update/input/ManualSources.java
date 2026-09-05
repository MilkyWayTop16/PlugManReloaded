package ru.milkyway.plugmanreloaded.update.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.UpdateModels.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.source.GithubSource;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ManualSources {

    private final PlugManReloaded plugin;
    private final ConcurrentHashMap<UUID, ManualSourceSession> activeSessions = new ConcurrentHashMap<>();

    public ManualSources(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    public Listener createListener() {
        return new ManualSourceListener(plugin, this);
    }

    public void start(@Nullable Player player, String pluginName, String mainClass) {
        if (player == null || pluginName == null || pluginName.isBlank()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        ManualSourceSession old = activeSessions.remove(playerId);
        if (old != null) {
            old.cancelTimeout();
        }

        int timeoutSeconds = plugin.getConfigManager().getManualSourceTimeoutSeconds();
        Object task = TaskScheduler.runSyncLater(plugin, () -> handleTimeout(playerId), timeoutSeconds * 20L);

        ManualSourceSession session = new ManualSourceSession(pluginName, mainClass, task);
        activeSessions.put(playerId, session);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("plugin", pluginName);
        placeholders.put("time", String.valueOf(timeoutSeconds));
        placeholders.put("cmd-type", "update");

        plugin.getConfigManager().executeActions(player, "actions.update.manual-source.waiting", placeholders);
    }

    public boolean cancel(@Nullable Player player) {
        if (player == null) return false;
        return cancel(player.getUniqueId());
    }

    public boolean cancel(@Nullable UUID playerId) {
        if (playerId == null) return false;
        ManualSourceSession session = activeSessions.remove(playerId);
        if (session != null) {
            session.cancelTimeout();
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                Map<String, String> map = new HashMap<>();
                map.put("plugin", session.getPluginName());
                plugin.getConfigManager().executeActions(player, "actions.update.manual-source.cancelled", map);
            }
            return true;
        }
        return false;
    }

    public void removeSession(@Nullable Player player) {
        if (player == null) return;
        removeSession(player.getUniqueId());
    }

    public void removeSession(@Nullable UUID playerId) {
        if (playerId == null) return;
        ManualSourceSession session = activeSessions.remove(playerId);
        if (session != null) {
            session.cancelTimeout();
        }
    }

    public @Nullable ManualSourceSession get(@Nullable Player player) {
        if (player == null) return null;
        return get(player.getUniqueId());
    }

    public @Nullable ManualSourceSession get(@Nullable UUID playerId) {
        if (playerId == null) return null;
        return activeSessions.get(playerId);
    }

    public void shutdown() {
        if (activeSessions != null) {
            for (ManualSourceSession session : activeSessions.values()) {
                session.cancelTimeout();
            }
            activeSessions.clear();
        }
    }

    private void handleTimeout(@Nullable UUID playerId) {
        if (playerId == null) return;
        ManualSourceSession session = activeSessions.remove(playerId);
        if (session != null) {
            session.cancelTimeout();
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                Map<String, String> map = new HashMap<>();
                map.put("plugin", session.getPluginName());
                plugin.getConfigManager().executeActions(player, "actions.update.manual-source.timeout", map);
            }
        }
    }

    public void processInput(Player player, @Nullable String rawInput) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        ManualSourceSession session = activeSessions.get(playerId);
        if (session == null || !player.isOnline()) {
            return;
        }

        if (rawInput == null || isCancelKeyword(rawInput.trim())) {
            cancel(playerId);
            return;
        }

        SourceUrlParser.ParseResult parsed = SourceUrlParser.parse(rawInput);
        if (!parsed.success()) {
            int timeoutSeconds = plugin.getConfigManager().getManualSourceTimeoutSeconds();
            Object task = TaskScheduler.runSyncLater(plugin, () -> handleTimeout(playerId), timeoutSeconds * 20L);
            session.setTimeoutTask(task);

            Map<String, String> map = new HashMap<>();
            map.put("plugin", session.getPluginName());
            String reason = text(parsed.errorReason());
            map.put("reason", reason);
            map.put("error", reason);
            map.put("time", String.valueOf(timeoutSeconds));
            plugin.getConfigManager().executeActions(player, "actions.update.manual-source.bad-url", map);
            return;
        }

        int timeoutSeconds = plugin.getConfigManager().getManualSourceTimeoutSeconds();
        Object task = TaskScheduler.runSyncLater(plugin, () -> handleTimeout(playerId), timeoutSeconds * 20L);
        session.setTimeoutTask(task);

        SourceCatalog.CatalogSource catalogSource = parsed.source();
        String notice = text(parsed.notice());

        TaskScheduler.runAsync(plugin, () -> verifyAndSave(playerId, session, catalogSource, notice));
    }

    private String text(@Nullable String messageKey) {
        return messageKey == null || messageKey.isBlank() ? "" : plugin.getConfigManager().text(messageKey);
    }

    private static boolean isCancelKeyword(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.equals("cancel") || lower.equals("отмена") || lower.equals("отменить")
                || lower.equals("exit") || lower.equals("stop") || lower.equals("/cancel") || lower.equals("-");
    }

    private static final String MS = "actions.update.manual-source.";

    private void verifyAndSave(UUID playerId, ManualSourceSession session, SourceCatalog.CatalogSource catalogSource, String notice) {
        UpdateSource source = plugin.getUpdateService().getSource(catalogSource.sourceId());
        if (source == null) {
            String reason = text(MS + "errors.unsupported-source")
                    .replace("{source}", String.valueOf(catalogSource.sourceId()));
            reportToPlayer(playerId, session, MS + "bad-url", MS + "bad-url",
                    Map.of("reason", reason, "error", reason));
            return;
        }

        try {
            if (source instanceof GithubSource github && github.isRateLimited()) {
                reportToPlayer(playerId, session, MS + "rate-limited", MS + "not-found",
                        Map.of("source", "GitHub", "ref", catalogSource.ref()));
                return;
            }

            UpdateSource.ProjectMatch match =
                    source.identifyFromCatalog(identityFor(session), catalogSource.ref(), catalogSource.options());
            if (match == null) {
                reportToPlayer(playerId, session, MS + "project-not-found", MS + "not-found",
                        Map.of("source", catalogSource.sourceId(), "ref", catalogSource.ref()));
                return;
            }

            List<RemoteVersion> versions = source.listVersions(match);
            if (versions == null || versions.isEmpty()) {
                reportToPlayer(playerId, session, MS + "no-versions", MS + "not-found",
                        Map.of("source", catalogSource.sourceId(), "ref", catalogSource.ref()));
                return;
            }

            RemoteVersion latest = versions.get(0);
            TaskScheduler.runSync(plugin, () -> saveSource(playerId, session, catalogSource, latest, notice));
        } catch (Throwable t) {
            Log.warn("manualsources.source-verify-failed", t,
                    "source", catalogSource.sourceId(), "ref", catalogSource.ref());
            reportToPlayer(playerId, session, MS + "not-found", MS + "not-found", Map.of(
                    "source", formatSourceDisplayName(catalogSource.sourceId()),
                    "ref", catalogSource.ref()));
        }
    }

    private PluginIdentity identityFor(ManualSourceSession session) {
        Plugin target = plugin.getPluginLifecycleManager().getPlugin(session.getPluginName());
        if (target != null) {
            PluginIdentity identity = plugin.getUpdateService().scanIdentity(target);
            if (identity != null) {
                return identity;
            }
        }
        File jar = target != null ? plugin.getPluginLifecycleManager().getPluginFile(target) : null;
        return new PluginIdentity(session.getPluginName(), session.getMainClass(), "1.0",
                List.of(), null, null, null, jar);
    }

    private void saveSource(UUID playerId, ManualSourceSession session, SourceCatalog.CatalogSource catalogSource,
                            RemoteVersion latest, String notice) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            session.cancelTimeout();
            activeSessions.remove(playerId, session);
            return;
        }
        if (activeSessions.get(playerId) != session) {
            return;
        }

        boolean saved = SourceCatalog.writeUserEntry(SourceCatalog.resolveFile(plugin), session.getPluginName(),
                session.getMainClass(), catalogSource, plugin.getConfigManager().getMainConfig().getLanguage());
        if (!saved) {
            Map<String, String> map = new HashMap<>();
            map.put("plugin", session.getPluginName());
            String actionKey = plugin.getConfigManager().getMessagesConfig().contains(MS + "save-failed")
                    ? MS + "save-failed"
                    : MS + "bad-url";
            plugin.getConfigManager().executeActions(player, actionKey, map);
            return;
        }

        session.cancelTimeout();
        activeSessions.remove(playerId, session);
        if (plugin.getUpdateService().getUpdateCache() != null) {
            plugin.getUpdateService().getUpdateCache().invalidateMiss(session.getPluginName());
        }
        plugin.getUpdateService().reload();

        String version = latest.versionNumber() != null ? latest.versionNumber() : "1.0";
        Map<String, String> map = new HashMap<>();
        map.put("plugin", session.getPluginName());
        map.put("source", formatSourceDisplayName(catalogSource.sourceId()));
        map.put("ref", catalogSource.ref());
        map.put("url", catalogSource.url() != null ? catalogSource.url() : "");
        map.put("version", version);
        map.put("latest", version);
        map.put("file", latest.fileName() != null ? latest.fileName() : session.getPluginName() + ".jar");
        map.put("channel", latest.channel() != null ? latest.channel().name() : "RELEASE");
        map.put("notice", notice != null ? notice : "");
        plugin.getConfigManager().executeActions(player, MS + "saved", map);
    }

    private void reportToPlayer(UUID playerId, ManualSourceSession session, String preferredKey,
                                String fallbackKey, Map<String, String> extra) {
        TaskScheduler.runSync(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || activeSessions.get(playerId) != session) {
                return;
            }
            Map<String, String> map = new HashMap<>();
            map.put("plugin", session.getPluginName());
            map.putAll(extra);
            String actionKey = plugin.getConfigManager().getMessagesConfig().contains(preferredKey)
                    ? preferredKey
                    : fallbackKey;
            plugin.getConfigManager().executeActions(player, actionKey, map);
        });
    }

    private static String formatSourceDisplayName(String sourceId) {
        return UpdateSource.displayName(sourceId);
    }

    public static class ManualSourceSession {
        private final String pluginName;
        private final String mainClass;
        private Object timeoutTask;
        private volatile long lastInputTime = 0L;

        public ManualSourceSession(String pluginName, String mainClass, Object timeoutTask) {
            this.pluginName = pluginName;
            this.mainClass = mainClass;
            this.timeoutTask = timeoutTask;
        }

        public boolean tryConsumeInput() {
            long now = System.currentTimeMillis();
            if (now - lastInputTime < 500L) {
                return false;
            }
            lastInputTime = now;
            return true;
        }

        public String getPluginName() {
            return pluginName;
        }

        public String getMainClass() {
            return mainClass;
        }

        public void setTimeoutTask(Object timeoutTask) {
            cancelTimeout();
            this.timeoutTask = timeoutTask;
        }

        public void cancelTimeout() {
            if (timeoutTask != null) {
                TaskScheduler.cancelTask(timeoutTask);
                timeoutTask = null;
            }
        }
    }

    public static class ManualSourceListener implements Listener {
        private final PlugManReloaded plugin;
        private final ManualSources manualSources;

        public ManualSourceListener(PlugManReloaded plugin, ManualSources manualSources) {
            this.plugin = plugin;
            this.manualSources = manualSources;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPaperChat(AsyncChatEvent event) {
            Player player = event.getPlayer();
            ManualSourceSession session = manualSources.get(player);
            if (session == null) {
                return;
            }

            event.setCancelled(true);
            try {
                event.viewers().clear();
            } catch (Exception t) {
                Log.debug("manualsourcelistener.hide-message-failed", t);
            }
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

            if (session.tryConsumeInput()) {
                TaskScheduler.runSync(plugin, () -> manualSources.processInput(player, text));
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onBukkitChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            ManualSourceSession session = manualSources.get(player);
            if (session == null) {
                return;
            }

            event.setCancelled(true);
            try {
                event.getRecipients().clear();
            } catch (Exception t) {
                Log.debug("manualsourcelistener.hide-message-failed", t);
            }
            String text = event.getMessage() != null ? event.getMessage().trim() : "";

            if (session.tryConsumeInput()) {
                TaskScheduler.runSync(plugin, () -> manualSources.processInput(player, text));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            if (event.getPlayer() != null) {
                manualSources.removeSession(event.getPlayer().getUniqueId());
            }
        }
    }
}
