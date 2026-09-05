package ru.milkyway.plugmanreloaded.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.BulkOperationResult;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.managers.UnloadSafetyChecker;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public abstract class AbstractSubCommand implements SubCommand {

    protected final PlugManReloaded plugin;

    public AbstractSubCommand(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(getPermission())) {
            sendNoPermission(sender);
            return true;
        }

        if (isPlayerOnly() && !(sender instanceof Player)) {
            sendConsoleNotAllowed(sender);
            return true;
        }

        CommandContext ctx = CommandContext.parse(sender, args, this);
        return handle(ctx);
    }

    protected abstract boolean handle(CommandContext ctx);

    protected void sendNoPermission(CommandSender sender) {
        plugin.getConfigManager().executeActions(sender, "errors.no-permission");
    }

    protected void sendConsoleNotAllowed(CommandSender sender) {
        plugin.getConfigManager().executeActions(sender, "errors.console-not-allowed");
    }

    protected void sendAction(CommandSender sender, String path) {
        plugin.getConfigManager().executeActions(sender, path);
    }

    protected void sendAction(CommandSender sender, String path, Map<String, String> placeholders) {
        plugin.getConfigManager().executeActions(sender, path, placeholders);
    }

    protected void sendResult(CommandSender sender, PluginResult result, Map<String, String> base, long elapsed) {
        Map<String, String> placeholders = new HashMap<>(base);
        placeholders.putAll(result.placeholders());
        placeholders.put("time", String.valueOf(elapsed));
        sendAction(sender, result.messageKey(), placeholders);
    }

    protected String riskReasonKey(String namespace, UnloadSafetyChecker.SafetyAssessment assessment) {
        if (!assessment.dependents().isEmpty()) {
            return "actions." + namespace + ".reasons.has-dependents";
        }
        String suffix = switch (assessment.riskLevel()) {
            case API_PROVIDER -> "api-provider";
            case LOW_LEVEL_NETWORK -> "netty";
            default -> "hostile";
        };
        return "actions." + namespace + ".reasons." + suffix;
    }

    protected boolean askRiskConfirmation(CommandSender sender, Plugin targetPlugin, String namespace,
                                          UnloadSafetyChecker.SafetyAssessment assessment,
                                          Map<String, String> base) {
        Set<String> dependents = assessment.dependents();
        String noneDependents = plugin.getConfigManager().getMessagesConfig()
                .getString("actions." + namespace + ".no-dependents", "");
        String depsList = dependents.isEmpty() ? noneDependents : String.join(", ", dependents);

        Map<String, String> placeholders = new HashMap<>(base);
        placeholders.put("reason", detailText(riskReasonKey(namespace, assessment)).replace("{dependents}", depsList));
        placeholders.put("dependents", depsList);
        placeholders.put("dependents-count", String.valueOf(dependents.size()));
        placeholders.put("token", plugin.getConfirmationManager()
                .createSession(sender, namespace, targetPlugin.getName()));
        sendAction(sender, namespace + ".confirm", placeholders);
        return true;
    }

    protected void sendBulkReport(CommandSender sender, String actionKey, BulkOperationResult result) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("count", String.valueOf(result.successCount()));
        placeholders.put("total", String.valueOf(result.total()));
        placeholders.put("time", String.valueOf(result.elapsedMillis()));
        placeholders.put("failed-count", String.valueOf(result.failedCount()));
        placeholders.put("failed-plugins", String.join(", ", result.failedPlugins()));

        if (result.failedCount() == result.total()) {
            sendAction(sender, actionKey + ".all-failed", placeholders);
        } else if (result.failedCount() > 0) {
            sendAction(sender, actionKey + ".all-partial", placeholders);
        } else {
            sendAction(sender, actionKey + ".all-success", placeholders);
        }
    }

    protected Map<String, String> getPluginPlaceholders(Plugin targetPlugin) {
        Map<String, String> map = new HashMap<>();
        if (targetPlugin != null) {
            String noneAuthors = plugin.getConfigManager().text("actions.info.none-authors");
            String noneMain = plugin.getConfigManager().text("actions.info.none-main");

            map.put("plugin", HexColors.escapeTags(targetPlugin.getName()));
            map.put("version", HexColors.escapeTags(PluginMetaHelper.getVersion(targetPlugin)));
            List<String> authors = targetPlugin.getDescription().getAuthors();
            String formattedAuthors = HexColors.escapeTags(authors.isEmpty() ? noneAuthors : String.join(", ", authors));
            map.put("authors", formattedAuthors);
            map.put("main", HexColors.escapeTags(targetPlugin.getDescription().getMain() != null ? targetPlugin.getDescription().getMain() : noneMain));
        }
        return map;
    }

    protected boolean handleCancel(CommandContext ctx, String actionType) {
        String canceledPlugin = ctx.target();
        String token = ctx.token();
        boolean consumed = plugin.getConfirmationManager().validateAndConsume(ctx.sender(), actionType, canceledPlugin, token);
        if (!consumed) {
            sendAction(ctx.sender(), "errors.confirm-expired");
            return true;
        }
        Map<String, String> map = new HashMap<>();
        map.put("plugin", canceledPlugin);
        Plugin p = plugin.getPluginLifecycleManager().getPlugin(canceledPlugin);
        if (p != null) {
            map.putAll(getPluginPlaceholders(p));
        }
        sendAction(ctx.sender(), actionType + ".cancelled", map);
        return true;
    }

    protected String detailText(@Nullable String detail) {
        return detail == null || detail.isBlank() ? "" : (plugin != null ? plugin.getConfigManager().text(detail) : detail);
    }

    protected boolean isPluginLocked(CommandSender sender, String pluginName, String actionType) {
        if (plugin.getDownloadService().getLockManager().isLocked(pluginName)) {
            String errKey = "errors." + actionType + "-failed";
            if (!plugin.getConfigManager().getMessagesConfig().contains("actions." + errKey)) {
                errKey = "errors.reload-failed";
            }
            sendAction(sender, errKey, Map.of(
                    "plugin", pluginName,
                    "error", detailText("actions.download.details.locked")
            ));
            return true;
        }
        return false;
    }

    protected boolean checkProtected(CommandSender sender, Plugin targetPlugin) {
        if (plugin.getPluginLifecycleManager().isProtected(targetPlugin)) {
            sendAction(sender, plugin.getPluginLifecycleManager().protectedReason(targetPlugin).messageKey(), getPluginPlaceholders(targetPlugin));
            return true;
        }
        return false;
    }

    protected boolean checkProtected(CommandSender sender, String pluginName) {
        if (plugin.getPluginLifecycleManager().isProtected(pluginName)) {
            sendAction(sender, plugin.getPluginLifecycleManager().protectedReason(pluginName).messageKey(), Map.of("plugin", pluginName));
            return true;
        }
        return false;
    }

    protected String joinTargetName(@Nullable String[] args, int from,
                                    Predicate<String> resolves) {
        if (args == null || from >= args.length) {
            return "";
        }
        String first = args[from];
        if (resolves.test(first)) {
            return first;
        }
        StringBuilder builder = new StringBuilder(first);
        for (int i = from + 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")
                    || ConfirmationManager.looksLikeToken(arg)) {
                break;
            }
            builder.append(' ').append(arg);
            if (resolves.test(builder.toString())) {
                return builder.toString();
            }
        }
        return first;
    }

    protected boolean isAllTarget(@Nullable String arg) {
        if (arg == null) return false;
        String s = arg.toLowerCase(Locale.ROOT);
        return s.equals("-all") || s.equals("-a") || s.equals("--all") || s.equals("all") || s.equals("*");
    }

    protected boolean hasAllFlag(@Nullable String[] args, int from) {
        if (args == null) return false;
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            if (arg != null) {
                String s = arg.toLowerCase(Locale.ROOT);
                if (s.equals("-all") || s.equals("-a") || s.equals("--all")) {
                    return true;
                }
            }
        }
        return false;
    }
}

