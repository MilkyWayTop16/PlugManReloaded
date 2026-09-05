package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.AbstractSubCommand;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.download.models.DownloadStatus;
import ru.milkyway.plugmanreloaded.download.models.DownloadResult;
import ru.milkyway.plugmanreloaded.download.models.DependencyTree;
import ru.milkyway.plugmanreloaded.download.models.SearchResultEntry;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import ru.milkyway.plugmanreloaded.utils.GameVersionFormatter;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DownloadCommand extends AbstractSubCommand {

    private final Map<String, DependencyTree> pendingTrees = new ConcurrentHashMap<>();

    public DownloadCommand(PlugManReloaded plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "download";
    }

    @Override
    public List<String> getAliases() {
        return List.of("dl", "get", "install");
    }

    @Override
    public String getPermission() {
        return "plugmanreloaded.download";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    protected boolean handle(CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (ctx.argCount() < 2) {
            sendAction(sender, "help.download");
            return true;
        }

        if (ctx.isCancel()) {
            return handleCancel(sender, ctx);
        }

        if (ctx.isConfirm()) {
            return handleConfirm(sender, ctx);
        }

        String rawQuery = ctx.target();
        if (rawQuery.isEmpty()) {
            sendAction(sender, "help.download");
            return true;
        }

        String preferredSource = ctx.getOption("source");
        boolean autoConfirm = ctx.hasFlag("y") || ctx.hasFlag("yes") || ctx.hasFlag("f") || ctx.hasFlag("force");
        boolean withSoftDeps = ctx.hasFlag("w") || ctx.hasFlag("with-soft-deps");
        String token = ctx.token();

        if (token != null && (plugin.getConfirmationManager().validateAndConsume(sender, "download", rawQuery, token)
                || pendingTrees.containsKey(token.toLowerCase(Locale.ROOT)))) {
            autoConfirm = true;
            DependencyTree savedTree = pendingTrees.remove(token.toLowerCase(Locale.ROOT));
            if (savedTree != null) {
                return executeConfirmedTree(sender, savedTree);
            }
        }

        if (rawQuery.startsWith("http://") || rawQuery.startsWith("https://") || rawQuery.contains("modrinth.com") || rawQuery.contains("hangar.papermc.io") || rawQuery.contains("spigotmc.org") || rawQuery.contains("github.com")) {
            return handleUrlDownload(sender, rawQuery, autoConfirm, withSoftDeps);
        }

        boolean isExplicitSelect = ctx.hasFlag("g") || ctx.hasFlag("get") || ctx.hasFlag("select");
        return handleSearchOrDownload(sender, rawQuery, preferredSource, autoConfirm, withSoftDeps, isExplicitSelect);
    }

    private boolean handleCancel(CommandSender sender, CommandContext ctx) {
        String token = ctx.token();
        if (token == null) {
            for (String arg : ctx.rawArgs()) {
                if (ConfirmationManager.looksLikeToken(arg)) {
                    token = arg;
                    break;
                }
            }
        }

        DependencyTree tree = null;
        if (token != null) {
            tree = pendingTrees.remove(token.toLowerCase(Locale.ROOT));
        }

        boolean consumed = false;
        if (token != null) {
            if (ctx.hasTarget()) {
                consumed = plugin.getConfirmationManager().validateAndConsume(sender, "download", ctx.target(), token);
            }
            if (!consumed && tree != null) {
                consumed = plugin.getConfirmationManager().validateAndConsume(sender, "download", tree.targetPluginName(), token);
            }
            if (!consumed) {
                consumed = plugin.getConfirmationManager().validateAndConsume(sender, "download", null, token);
            }
        }

        if (tree == null && !consumed) {
            sendAction(sender, "errors.confirm-expired");
            return true;
        }

        String resolvedName = tree != null ? tree.targetPluginName() : (ctx.hasTarget() ? ctx.target() : detailText("actions.download.details.unknown-plugin"));
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("plugin", resolvedName);
        sendAction(sender, "download.cancelled", placeholders);
        return true;
    }

    private boolean handleConfirm(CommandSender sender, CommandContext ctx) {
        String token = ctx.token();
        if (token == null) {
            for (String arg : ctx.rawArgs()) {
                if (ConfirmationManager.looksLikeToken(arg)) {
                    token = arg;
                    break;
                }
            }
        }
        if (token == null) {
            sendAction(sender, "download.not-found", Map.of("query", ctx.hasTarget() ? ctx.target() : "—"));
            return true;
        }

        DependencyTree tree = pendingTrees.remove(token.toLowerCase(Locale.ROOT));
        if (tree == null) {
            Map<String, String> map = new HashMap<>();
            map.put("plugin", ctx.hasTarget() ? ctx.target() : detailText("actions.download.details.unknown-plugin"));
            sendAction(sender, "download.timeout", map);
            return true;
        }

        plugin.getConfirmationManager().consumeIfPresent(sender, "download", tree.targetPluginName());
        if (ctx.hasTarget()) {
            plugin.getConfirmationManager().consumeIfPresent(sender, "download", ctx.target());
        }
        return executeConfirmedTree(sender, tree);
    }

    private boolean executeConfirmedTree(CommandSender sender, DependencyTree tree) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("plugin", tree.targetPluginName());
        placeholders.put("current", "1");
        placeholders.put("total", String.valueOf(tree.requiredDependencies().size() + 1));
        sendAction(sender, "download.installing", placeholders);

        plugin.getDownloadService().confirmAndExecuteTree(tree, result -> handleDownloadResult(sender, result));
        return true;
    }

    private boolean handleUrlDownload(CommandSender sender, String url, boolean autoConfirm, boolean withSoftDeps) {
        Map<String, String> searchingMap = new HashMap<>();
        searchingMap.put("query", url);
        sendAction(sender, "download.searching", searchingMap);

        plugin.getDownloadService().downloadFromUrl(
                url, autoConfirm, withSoftDeps,
                result -> handleDownloadResult(sender, result),
                tree -> promptDependencies(sender, tree)
        );
        return true;
    }

    private boolean handleSearchOrDownload(CommandSender sender, String query, String preferredSource, boolean autoConfirm, boolean withSoftDeps, boolean isExplicitSelect) {
        Map<String, String> searchingMap = new HashMap<>();
        searchingMap.put("query", query);
        if (preferredSource != null && !preferredSource.isBlank()) {
            searchingMap.put("source", formatSourceName(preferredSource));
            searchingMap.put("source-id", preferredSource);
            sendAction(sender, "download.searching-source", searchingMap);
        } else {
            sendAction(sender, "download.searching", searchingMap);
        }

        TaskScheduler.runAsync(plugin, () -> {
            List<SearchResultEntry> hits = plugin.getDownloadService().search(query, preferredSource, 4);

            if (hits.isEmpty()) {
                TaskScheduler.runSync(plugin, () -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("query", query);
                    sendAction(sender, "download.not-found", map);
                });
                return;
            }

            if (isExplicitSelect || autoConfirm) {
                SearchResultEntry top = hits.get(0);
                TaskScheduler.runSync(plugin, () -> {
                    plugin.getDownloadService().resolveAndDownload(
                            top, autoConfirm, withSoftDeps,
                            result -> handleDownloadResult(sender, result),
                            tree -> promptDependencies(sender, tree)
                    );
                });
                return;
            }

            TaskScheduler.runSync(plugin, () -> displaySearchResults(sender, query, hits));
        });
        return true;
    }

    private void displaySearchResults(CommandSender sender, String query, List<SearchResultEntry> hits) {
        FileConfiguration config = plugin.getConfigManager().getMessagesConfig();
        StringBuilder cardsBuilder = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                cardsBuilder.append("\n");
            }
            cardsBuilder.append(formatCard(config, hits.get(i), i + 1));
        }

        String cardsStr = cardsBuilder.toString();
        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("query", query);
        headerMap.put("cards", cardsStr);
        headerMap.put("results", cardsStr);
        headerMap.put("plugins", cardsStr);
        headerMap.put("count", String.valueOf(hits.size()));

        sendAction(sender, "download.search-results-header", headerMap);
    }

    private String formatCard(FileConfiguration config, SearchResultEntry entry, int index) {
        Map<String, String> p = new HashMap<>();
        p.put("index", String.valueOf(index));
        p.put("title", HexColors.escapeTags(entry.title()));
        p.put("author", HexColors.escapeTags(entry.author()));
        p.put("source-id", entry.sourceId());
        p.put("source", formatSourceName(entry.sourceId()));
        String rawVer = entry.version() != null && !entry.version().isBlank() ? entry.version() : "";
        String versionLatest = plugin.getConfigManager().text("actions.download.version-latest");
        String cleanVer = !rawVer.isBlank() ? PluginMetaHelper.cleanVersion(rawVer) : versionLatest;
        p.put("version", cleanVer);
        p.put("downloads", formatCount(entry.downloads()));
        p.put("stars", String.valueOf(entry.stars()));
        p.put("description", HexColors.escapeTags(entry.description() != null ? entry.description() : ""));
        p.put("url", entry.url() != null ? entry.url() : "");
        p.put("game-versions", GameVersionFormatter.formatRanges(entry.gameVersions()));
        p.put("license", plugin.getConfigManager().text("actions.download.default-license"));
        p.put("project", entry.projectId());

        String dlBtn = formatDownloadButton(config, p);
        String infoBtn = formatInfoButton(config, p);
        String webBtn = formatWebButton(config, p);

        p.put("download-button", dlBtn);
        p.put("info-button", infoBtn);
        p.put("web-button", webBtn);

        List<String> cardTemplate = config.getStringList("actions.download.search-card");
        if (cardTemplate.isEmpty()) {
            cardTemplate = List.of(
                    "  &#FFFF00{index}. &#FFFF00«{title}» &f(&#FFFF00v{version}&f) &8by &f{author}",
                    "     &#FFFF00◆ &fSite: &#FFFF00{source} &8• &fDownloads: &#00FF5A{downloads} &8• &fStars: &#FFFF00★ {stars}",
                    "     {download-button}   {info-button}   {web-button}",
                    ""
            );
        }

        StringBuilder cardBuilder = new StringBuilder();
        for (int i = 0; i < cardTemplate.size(); i++) {
            String line = cardTemplate.get(i);
            if (line.startsWith("[message] ")) {
                line = line.substring(10);
            } else if (line.equals("[message]")) {
                line = "";
            }
            cardBuilder.append(applyPlaceholders(line, p));
            if (i < cardTemplate.size() - 1) {
                cardBuilder.append("\n");
            }
        }
        return cardBuilder.toString();
    }

    private String formatDownloadButton(FileConfiguration config, Map<String, String> p) {
        String text = plugin.getConfigManager().text("actions.download.buttons.download.text");
        List<String> hoverList = config.getStringList("actions.download.buttons.download.hover");
        String rawHover = !hoverList.isEmpty() ? String.join("\n", hoverList) : "\n &#00FF5A▶ &fClick to &#00FF5Adownload &fand &#00FF5Aactivate &fthe plugin &#00FF5A«{title}» \n";
        String sourceId = p.get("source-id");
        String srcFlag = (sourceId != null && !sourceId.isBlank()) ? " -s " + sourceId : "";
        String cmd = "/plm download " + p.getOrDefault("project", p.get("title")) + srcFlag + " -g";

        text = applyPlaceholders(text, p);
        rawHover = applyPlaceholders(rawHover, p);
        String miniHover = !rawHover.isBlank() ? HexColors.toMiniMessage(rawHover) : "";

        if (!miniHover.isBlank()) {
            return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
        }
        return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\">" + text + "</click>";
    }

    private String formatInfoButton(FileConfiguration config, Map<String, String> p) {
        String text = plugin.getConfigManager().text("actions.download.buttons.info.text");
        List<String> hoverList = config.getStringList("actions.download.buttons.info.hover");
        String rawHover = !hoverList.isEmpty() ? String.join("\n", hoverList) : "";
        String url = p.get("url");

        text = applyPlaceholders(text, p);
        rawHover = applyPlaceholders(rawHover, p);
        String miniHover = !rawHover.isBlank() ? HexColors.toMiniMessage(rawHover) : "";

        if (url != null && !url.isBlank()) {
            if (!miniHover.isBlank()) {
                return "<click:open_url:\"" + url.replace("\"", "") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
            }
            return "<click:open_url:\"" + url.replace("\"", "") + "\">" + text + "</click>";
        }

        if (!miniHover.isBlank()) {
            return "<hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover>";
        }
        return text;
    }

    private String formatWebButton(FileConfiguration config, Map<String, String> p) {
        String url = p.get("url");
        if (url == null || url.isBlank()) return "";
        String text = plugin.getConfigManager().text("actions.download.buttons.web.text");
        List<String> hoverList = config.getStringList("actions.download.buttons.web.hover");
        String rawHover = !hoverList.isEmpty() ? String.join("\n", hoverList) : "\n &#00D4FF▶ &fClick to &#00D4FFopen &fthe project page in your browser:\n &7" + url + " \n";

        text = applyPlaceholders(text, p);
        rawHover = applyPlaceholders(rawHover, p);
        String miniHover = !rawHover.isBlank() ? HexColors.toMiniMessage(rawHover) : "";

        if (!miniHover.isBlank()) {
            return "<click:open_url:\"" + url.replace("\"", "") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
        }
        return "<click:open_url:\"" + url.replace("\"", "") + "\">" + text + "</click>";
    }

    private String applyPlaceholders(@Nullable String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null || placeholders.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String k = e.getKey();
            String v = e.getValue() != null ? e.getValue() : "";
            result = result.replace("{" + k + "}", v).replace("%" + k + "%", v);
        }
        return result;
    }

    private void promptDependencies(CommandSender sender, DependencyTree tree) {
        String token = plugin.getConfirmationManager().createSession(sender, "download", tree.targetPluginName());
        pendingTrees.put(token.toLowerCase(Locale.ROOT), tree);

        FileConfiguration config = plugin.getConfigManager().getMessagesConfig();
        SearchResultEntry target = tree.targetEntry();

        Map<String, String> map = new HashMap<>();
        map.put("plugin", tree.targetPluginName());
        map.put("title", HexColors.escapeTags(target.title() != null ? target.title() : tree.targetPluginName()));
        map.put("token", token);
        String rawVer = target.version() != null && !target.version().isBlank() ? target.version() : "";
        String versionLatest = plugin.getConfigManager().text("actions.download.version-latest");
        map.put("version", !rawVer.isBlank() ? PluginMetaHelper.cleanVersion(rawVer) : versionLatest);
        String authorUnknown = plugin.getConfigManager().text("actions.download.author-unknown");
        map.put("author", HexColors.escapeTags(target.author() != null ? target.author() : authorUnknown));
        String url = target.url() != null ? target.url() : "";
        map.put("source-id", target.sourceId() != null ? target.sourceId() : "");
        map.put("source", formatSourceName(target.sourceId()));
        map.put("downloads", formatCount(target.downloads()));
        map.put("stars", String.valueOf(target.stars()));
        map.put("description", HexColors.escapeTags(target.description() != null ? target.description() : ""));
        map.put("url", url);
        map.put("raw-url", url);
        map.put("game-versions", GameVersionFormatter.formatRanges(target.gameVersions()));
        map.put("license", plugin.getConfigManager().text("actions.download.default-license"));
        map.put("project", target.projectId() != null ? target.projectId() : tree.targetPluginName());

        String confirmBtn = formatConfirmDownloadButton(config, map);
        String confirmDepsBtn = formatConfirmDownloadDepsButton(config, map);
        String cancelBtn = formatCancelDownloadButton(config, map);

        map.put("confirm-download-button", confirmBtn);
        map.put("single-button", confirmBtn);
        map.put("confirm-download-deps-button", confirmDepsBtn);
        map.put("deps-button", confirmDepsBtn);
        map.put("cancel-button", cancelBtn);

        if (tree.hasMissing()) {
            StringBuilder depList = new StringBuilder();
            for (SearchResultEntry req : tree.requiredDependencies()) {
                depList.append("    &#00FF5A◆ &f").append(HexColors.escapeTags(req.title())).append(" &8(").append(formatSourceName(req.sourceId())).append(")\n");
            }
            for (String dis : tree.existingDisabledToEnable()) {
                depList.append("    &#FFFF00◆ &f").append(dis).append(" &7(disabled on server)\n");
            }
            for (String unl : tree.existingUnloadedToLoad()) {
                depList.append("    &#FFAA00◆ &f").append(unl).append(" &7(found in plugins/)\n");
            }
            map.put("dependency-list", depList.toString().trim());
            sendAction(sender, "download.confirm-dependencies", map);
        } else {
            sendAction(sender, "download.confirm-single", map);
        }

        final String sessionToken = token;
        final String targetPlugin = tree.targetPluginName();
        TaskScheduler.runSyncLater(plugin, () -> {
            DependencyTree expired = pendingTrees.remove(sessionToken.toLowerCase(Locale.ROOT));
            if (expired != null) {
                plugin.getConfirmationManager().consumeIfPresent(sender, "download", targetPlugin);
            }
        }, 60 * 20L);
    }

    private String formatConfirmDownloadButton(FileConfiguration config, Map<String, String> p) {
        String text = plugin.getConfigManager().text("actions.download.buttons.confirm-single.text");
        List<String> hoverList = config.getStringList("actions.download.buttons.confirm-single.hover");
        String rawHover = !hoverList.isEmpty() ? String.join("\n", hoverList) : "\n &#22FF00▶ &fClick to &#22FF00download &fand &#22FF00activate &fthe plugin &#22FF00«{plugin}» \n";
        String cmd = "/plm download " + p.get("plugin") + " confirm " + p.get("token");

        text = applyPlaceholders(text, p);
        rawHover = applyPlaceholders(rawHover, p);
        String miniHover = !rawHover.isBlank() ? HexColors.toMiniMessage(rawHover) : "";

        if (!miniHover.isBlank()) {
            return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
        }
        return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\">" + text + "</click>";
    }

    private String formatConfirmDownloadDepsButton(FileConfiguration config, Map<String, String> p) {
        String text = plugin.getConfigManager().text("actions.download.buttons.confirm-deps.text");
        List<String> hoverList = config.getStringList("actions.download.buttons.confirm-deps.hover");
        String rawHover = !hoverList.isEmpty() ? String.join("\n", hoverList) : "\n &#22FF00▶ &fClick to automatically &#22FF00download its dependencies &fand &#22FF00enable &fthe plugin \n";
        String cmd = "/plm download " + p.get("plugin") + " confirm " + p.get("token");

        text = applyPlaceholders(text, p);
        rawHover = applyPlaceholders(rawHover, p);
        String miniHover = !rawHover.isBlank() ? HexColors.toMiniMessage(rawHover) : "";

        if (!miniHover.isBlank()) {
            return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
        }
        return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\">" + text + "</click>";
    }

    private String formatCancelDownloadButton(FileConfiguration config, Map<String, String> p) {
        String text = plugin.getConfigManager().text("actions.download.buttons.cancel.text");
        List<String> hoverList = config.getStringList("actions.download.buttons.cancel.hover");
        String rawHover = !hoverList.isEmpty() ? String.join("\n", hoverList) : "\n &#FB8808◆ &fClick to &#FB8808cancel &fthe installation \n";
        String cmd = "/plm download cancel " + p.get("plugin") + " " + p.get("token");

        text = applyPlaceholders(text, p);
        rawHover = applyPlaceholders(rawHover, p);
        String miniHover = !rawHover.isBlank() ? HexColors.toMiniMessage(rawHover) : "";

        if (!miniHover.isBlank()) {
            return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
        }
        return "<click:run_command:\"" + cmd.replace("\"", "\\\"") + "\">" + text + "</click>";
    }

    private void handleDownloadResult(CommandSender sender, DownloadResult result) {
        FileConfiguration config = plugin.getConfigManager().getMessagesConfig();
        Map<String, String> map = new HashMap<>();
        map.put("plugin", result.pluginName() != null ? result.pluginName() : detailText("actions.download.details.unknown-plugin"));
        map.put("version", result.version() != null ? result.version() : "1.0");
        map.put("source", formatSourceName(result.sourceId()));
        String detail = detailText(result.message());
        map.put("detail", detail);
        map.put("deps", detail);
        map.put("chain", detail);
        map.put("current-java", String.valueOf(ServerProfile.detectJavaVersion()));
        map.put("required-java", extractRequiredJava(result.message()));

        String resolvedReason = resolveReason(config, result, map);
        map.put("reason", resolvedReason);

        if (result.outcome() == DownloadStatus.INSTALLED) {
            sendAction(sender, "download.installed", map);
        } else if (result.outcome() == DownloadStatus.BOOTSTRAPPER_RESTART_REQUIRED) {
            sendAction(sender, "download.bootstrapper-installed", map);
        } else {
            sendAction(sender, "download.failed", map);
        }
    }

    private String resolveReason(FileConfiguration config, DownloadResult result, Map<String, String> placeholders) {
        String detail = detailText(result.message());
        if (!detail.equals(result.message())) {
            return applyPlaceholders(detail, placeholders);
        }

        if (result.outcome() == null) {
            return detail;
        }

        String key = switch (result.outcome()) {
            case INCOMPATIBLE_JAVA -> "incompatible-java";
            case INVALID_PLUGIN -> "invalid-jar";
            case INVALID_MANIFEST -> "invalid-manifest";
            case HASH_MISMATCH -> "hash-mismatch";
            case DOWNLOAD_FAILED -> "download-failed";
            case DEPENDENCIES_REQUIRED -> "missing-deps";
            case CIRCULAR_DEPENDENCIES -> "circular-deps";
            case LOCKED -> "locked";
            case ROLLED_BACK -> "rolled-back";
            case ACTIVATION_FAILED -> "activation-failed";
            case WRITE_FAILED -> "write-failed";
            case RATE_LIMITED -> "rate-limited";
            default -> null;
        };

        String template = null;
        if (key != null) {
            template = config.getString("actions.download.reasons." + key);
        }

        if (template == null || template.isBlank()) {
            return detail;
        }

        return applyPlaceholders(template, placeholders);
    }

    private static String formatSourceName(String id) {
        return UpdateSource.displayName(id);
    }

    private String formatCount(long count) {
        String thousand = plugin.getConfigManager().text("actions.download.count-units.thousand");
        String million = plugin.getConfigManager().text("actions.download.count-units.million");

        if (count >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f%s", count / 1_000_000.0, million);
        }
        if (count >= 1_000) {
            return String.format(Locale.ROOT, "%.1f%s", count / 1_000.0, thousand);
        }
        return String.valueOf(count);
    }

    public static String extractRequiredJava(String message) {
        if (message != null && message.contains("Java ")) {
            int idx = message.indexOf("Java ");
            String sub = message.substring(idx + 5);
            int comma = sub.indexOf(',');
            if (comma > 0) sub = sub.substring(0, comma).trim();
            if (sub.endsWith("+")) sub = sub.substring(0, sub.length() - 1).trim();
            return sub;
        }
        return "21";
    }
}

