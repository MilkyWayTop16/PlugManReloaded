package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ConfirmationManager implements Listener {

    private static final long TIMEOUT_MS = 60_000L;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-fA-F]{6,8}");

    public record ConfirmationSession(String senderKey, String commandType, String pluginName, String token, String payload, long timestamp) {
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TIMEOUT_MS;
        }
    }

    private final Map<String, ConfirmationSession> pendingSessions = new ConcurrentHashMap<>();
    private Object cleanupTask;

    public ConfirmationManager() {
        this(null);
    }

    public ConfirmationManager(Plugin plugin) {
        if (plugin != null) {
            this.cleanupTask = TaskScheduler.runAsyncTimer(plugin, () -> {
                pendingSessions.values().removeIf(ConfirmationSession::isExpired);
            }, 60L, 60L, TimeUnit.SECONDS);
        }
    }

    public void shutdown() {
        if (cleanupTask != null) {
            TaskScheduler.cancelTask(cleanupTask);
            cleanupTask = null;
        }
        if (pendingSessions != null) {
            pendingSessions.clear();
        }
    }

    public boolean hasActiveCleanupTask() {
        return cleanupTask != null;
    }

    private String getSenderKey(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId().toString();
        }
        return "CONSOLE";
    }

    public static boolean looksLikeToken(String value) {
        return value != null && TOKEN_PATTERN.matcher(value).matches();
    }

    public String createSession(CommandSender sender, String commandType, String pluginName) {
        return createSession(sender, commandType, pluginName, null);
    }

    public String createSession(CommandSender sender, String commandType, String pluginName, String payload) {
        String token = String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
        pendingSessions.put(token, new ConfirmationSession(
                getSenderKey(sender),
                commandType.toLowerCase(Locale.ROOT),
                pluginName.toLowerCase(Locale.ROOT),
                token,
                payload,
                System.currentTimeMillis()
        ));
        return token;
    }

    public @Nullable String peekPayload(CommandSender sender, String commandType, String pluginName) {
        ConfirmationSession bestMatch = null;
        String key = getSenderKey(sender);

        for (ConfirmationSession session : pendingSessions.values()) {
            if (session.isExpired()) continue;
            if (!session.senderKey().equals(key)) continue;
            if (!session.commandType().equalsIgnoreCase(commandType)) continue;
            if (!session.pluginName().equalsIgnoreCase(pluginName)) continue;

            if (bestMatch == null || session.timestamp() > bestMatch.timestamp()) {
                bestMatch = session;
            }
        }

        return bestMatch != null ? bestMatch.payload() : null;
    }

    public boolean validateAndConsume(CommandSender sender, String commandType, @Nullable String pluginName, @Nullable String token) {
        if (token == null) return false;

        String key = token.toLowerCase(Locale.ROOT);
        ConfirmationSession session = pendingSessions.get(key);
        if (session == null || session.isExpired()) {
            if (session != null) {
                pendingSessions.remove(key);
            }
            return false;
        }

        if (!session.senderKey().equals(getSenderKey(sender))) {
            return false;
        }

        if (!session.commandType().equalsIgnoreCase(commandType)) {
            return false;
        }

        if (pluginName != null && !pluginName.isEmpty() && !session.pluginName().equalsIgnoreCase(pluginName)) {
            return false;
        }

        return pendingSessions.remove(key, session);
    }

    public void consumeIfPresent(CommandSender sender, String commandType, String pluginName) {
        String key = getSenderKey(sender);
        pendingSessions.values().removeIf(session ->
            session.senderKey().equals(key)
            && session.commandType().equalsIgnoreCase(commandType)
            && session.pluginName().equalsIgnoreCase(pluginName)
        );
    }

    public void removeAll(CommandSender sender) {
        String key = getSenderKey(sender);
        pendingSessions.values().removeIf(session -> session.senderKey().equals(key));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String key = event.getPlayer().getUniqueId().toString();
        pendingSessions.values().removeIf(session -> session.senderKey().equals(key));
    }
}
