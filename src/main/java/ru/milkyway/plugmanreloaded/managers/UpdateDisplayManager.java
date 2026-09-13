package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.UpdateModels.ReleaseChannel;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import ru.milkyway.plugmanreloaded.utils.HexColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class UpdateDisplayManager {

    public record SummaryEntry(String text, String hover, String command) {
        private static final String DEFAULT_TEXT =
                "&#FFFF00◆ &f{plugin} &7{current} &f→ &#00FF5A{latest} &7({source}, {channel})";
        private static final String DEFAULT_HOVER =
                "\n &#00FF5A▶ &fUpdate for &#00FF5A«{plugin}» \n\n &#FFFF00◆ &fCurrent version: &7v{current} \n"
                + " &#00FF5A◆ &fNew version: &#00FF5Av{latest} \n &#FFFF00◆ &fSource: &#FFFF00{source} &7({channel}) \n\n"
                + " &#00FF5A▶ &fClick to &#00FF5Adownload and install &fthe update \n";
        private static final String DEFAULT_COMMAND = "/plm update {plugin}";
    }

    public record UpdateTally(List<UpdateCandidate> withUpdates, int upToDate, int noSource, int problems) {}

    private final @Nullable ConfigManager configManager;

    public UpdateDisplayManager(@Nullable ConfigManager configManager) {
        this.configManager = configManager;
    }

    private @Nullable FileConfiguration config() {
        return configManager != null ? configManager.getMessagesConfig() : null;
    }

    public @NotNull UpdateTally tally(final @NotNull List<UpdateCandidate> results,
                                      final @Nullable Consumer<UpdateCandidate> onUpdateFound) {
        final List<UpdateCandidate> withUpdates = new ArrayList<>();
        int upToDate = 0;
        int noSource = 0;
        int problems = 0;

        for (final UpdateCandidate candidate : results) {
            if (candidate.status().hasNewerVersion()) {
                withUpdates.add(candidate);
                if (onUpdateFound != null) {
                    onUpdateFound.accept(candidate);
                }
            } else if (candidate.status() == UpdateStatus.UP_TO_DATE || candidate.status() == UpdateStatus.PENDING_RESTART) {
                upToDate++;
            } else if (candidate.status() == UpdateStatus.NO_SOURCE) {
                noSource++;
            } else {
                problems++;
            }
        }
        return new UpdateTally(withUpdates, upToDate, noSource, problems);
    }

    public @NotNull Map<String, String> summarize(final int total, final @NotNull UpdateTally tally) {
        final Map<String, String> summary = new HashMap<>();
        summary.put("total", String.valueOf(total));
        summary.put("available", String.valueOf(tally.withUpdates().size()));
        summary.put("count", String.valueOf(tally.withUpdates().size()));
        summary.put("up-to-date", String.valueOf(tally.upToDate()));
        summary.put("no-source", String.valueOf(tally.noSource()));
        summary.put("problems", String.valueOf(tally.problems()));
        summary.put("cmd-type", "update");
        summary.put("plugin", "all");
        return summary;
    }

    public @NotNull SummaryEntry readSummaryEntry() {
        final FileConfiguration cfg = config();
        final String path = "actions.update.summary-entry";
        if (cfg == null) {
            return new SummaryEntry(SummaryEntry.DEFAULT_TEXT, SummaryEntry.DEFAULT_HOVER, SummaryEntry.DEFAULT_COMMAND);
        }

        if (cfg.isConfigurationSection(path)) {
            final String text = cfg.getString(path + ".text", SummaryEntry.DEFAULT_TEXT);
            String hover = SummaryEntry.DEFAULT_HOVER;
            if (cfg.isList(path + ".hover")) {
                hover = String.join("<newline>", cfg.getStringList(path + ".hover"));
            } else if (cfg.isString(path + ".hover")) {
                hover = cfg.getString(path + ".hover", hover);
            }
            return new SummaryEntry(text, hover, cfg.getString(path + ".command", SummaryEntry.DEFAULT_COMMAND));
        }

        String custom = null;
        if (cfg.isString(path)) {
            custom = cfg.getString(path);
        } else if (cfg.isList(path)) {
            final List<String> list = cfg.getStringList(path);
            custom = list.isEmpty() ? null : list.get(0);
        }
        if (custom == null || custom.isBlank()) {
            return new SummaryEntry(SummaryEntry.DEFAULT_TEXT, SummaryEntry.DEFAULT_HOVER, SummaryEntry.DEFAULT_COMMAND);
        }
        final String text = custom.startsWith("[message]") ? custom.substring(9).trim() : custom.trim();
        return new SummaryEntry(text, SummaryEntry.DEFAULT_HOVER, SummaryEntry.DEFAULT_COMMAND);
    }

    public @NotNull String renderSummaryList(final @NotNull List<UpdateCandidate> withUpdates) {
        final SummaryEntry template = readSummaryEntry();
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < withUpdates.size(); i++) {
            if (i > 0) builder.append("\n");
            builder.append(renderSummaryLine(withUpdates.get(i), template));
        }
        return builder.toString();
    }

    public @NotNull String renderSummaryLine(final @NotNull UpdateCandidate candidate, final @NotNull SummaryEntry template) {
        final Map<String, String> values = placeholders(candidate);
        final String text = applyPlaceholders(template.text(), values);
        String hover = applyPlaceholders(template.hover(), values);
        String command = applyPlaceholders(template.command(), values);

        if (isPaid(candidate)) {
            command = "";
            hover = "";
        } else if (candidate.version() != null && candidate.version().channel() != null && candidate.version().channel().isPrerelease()) {
            if (command.startsWith("/plm update") && !command.contains("-pre") && !command.contains("--prerelease") && !command.contains("--beta")) {
                command = command + " -pre";
            }
        }

        final String miniHover = hover.isBlank() ? "" : HexColors.toMiniMessage(hover);
        if (!command.isBlank() && !miniHover.isBlank()) {
            return "<click:run_command:\"" + command.replace("\"", "\\\"") + "\"><hover:show_text:\""
                    + miniHover.replace("\"", "'") + "\">" + text + "</hover></click>";
        }
        if (!command.isBlank()) {
            return "<click:run_command:\"" + command.replace("\"", "\\\"") + "\">" + text + "</click>";
        }
        if (!miniHover.isBlank()) {
            return "<hover:show_text:\"" + miniHover.replace("\"", "'") + "\">" + text + "</hover>";
        }
        return text;
    }

    public @NotNull Map<String, String> placeholders(final @NotNull UpdateCandidate candidate) {
        final Map<String, String> map = new HashMap<>();
        map.put("cmd-type", "update");
        map.put("plugin", HexColors.escapeTags(candidate.identity().pluginName()));
        final String currentVer = HexColors.escapeTags(cleanVersion(candidate.identity().currentVersion()));
        final String latestVer = HexColors.escapeTags(cleanVersion(candidate.remoteVersionNumber()));
        map.put("current", currentVer);
        map.put("latest", latestVer);
        map.put("version", latestVer);

        final String noneAuthors = configManager != null ? configManager.text("actions.info.none-authors") : "—";
        final String authors = HexColors.escapeTags(candidate.identity().authors() != null && !candidate.identity().authors().isEmpty()
                ? String.join(", ", candidate.identity().authors())
                : noneAuthors);
        map.put("author", authors);

        final String channelKey = candidate.version() != null && candidate.version().channel() != null
                ? candidate.version().channel().name().toLowerCase(Locale.ROOT)
                : "unknown";
        map.put("channel", formatChannel(channelKey));

        final String sourceId = candidate.version() != null ? candidate.version().sourceId() : "unknown";
        map.put("source", formatSource(sourceId));

        final FileConfiguration cfg = config();
        map.put("reason", cfg != null ? cfg.getString(candidate.reason().messageKey(), "") : "");

        final String rawPageUrl = candidate.pageUrl() != null ? candidate.pageUrl() : "—";
        map.put("raw-url", rawPageUrl);
        map.put("url", formatLink(rawPageUrl));
        map.put("file", candidate.version() != null && candidate.version().fileName() != null ? candidate.version().fileName() : "—");
        return map;
    }

    public @NotNull String formatLink(final @Nullable String url) {
        return formatLink(url, config());
    }

    public static @NotNull String formatLink(final @Nullable String url, final @Nullable FileConfiguration config) {
        if (url == null || url.isBlank() || url.equals("—")) {
            return "—";
        }
        List<String> hoverList = config != null ? config.getStringList("actions.update.link.hover") : Collections.emptyList();
        if (hoverList.isEmpty()) {
            if (config != null && config.isList("actions.link.hover")) {
                hoverList = config.getStringList("actions.link.hover");
            }
        }
        final String rawHover;
        if (!hoverList.isEmpty()) {
            rawHover = String.join("\n", hoverList);
        } else {
            rawHover = "\n &#FFFF00◆ &fClick to &#FFFF00open &fthe link\n";
        }
        final String miniHover = HexColors.toMiniMessage(rawHover);
        return "<click:open_url:\"" + url.replace("\"", "") + "\"><hover:show_text:\"" + miniHover.replace("\"", "'") + "\"><underlined>" + url + "</underlined></hover></click>";
    }

    public @NotNull String formatChannel(final @Nullable String channelKey) {
        return formatChannel(channelKey, config());
    }

    public static @NotNull String formatChannel(final @Nullable String channelKey, final @Nullable FileConfiguration config) {
        final String effectiveKey = (channelKey == null || channelKey.isBlank() || channelKey.equalsIgnoreCase("unknown"))
                ? "release"
                : channelKey;
        final String val = config != null ? config.getString("actions.update.channels." + effectiveKey.toLowerCase(Locale.ROOT)) : null;
        if (val != null && !val.isBlank()) {
            return val;
        }
        return switch (effectiveKey.toLowerCase(Locale.ROOT)) {
            case "beta" -> "BETA";
            case "alpha" -> "ALPHA";
            default -> "RELEASE";
        };
    }

    public @NotNull String formatSource(final @Nullable String sourceId) {
        return formatSource(sourceId, config());
    }

    public static @NotNull String formatSource(final @Nullable String sourceId, final @Nullable FileConfiguration config) {
        final String effectiveId = (sourceId == null || sourceId.isBlank()) ? "unknown" : sourceId;
        final String val = config != null ? config.getString("actions.update.sources." + effectiveId.toLowerCase(Locale.ROOT)) : null;
        if (val != null && !val.isBlank()) {
            return val;
        }
        if (UpdateSource.isPaidSource(effectiveId)) {
            return UpdateSource.displayName(effectiveId.replace("-premium", "")) + " (paid)";
        }
        return UpdateSource.displayName(effectiveId);
    }

    public static @NotNull String cleanVersion(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        final String trimmed = value.trim();
        final String stripped = trimmed.replaceFirst("^[vV]+", "");
        return stripped.isEmpty() ? trimmed : stripped;
    }

    public static boolean isPaid(final @Nullable UpdateCandidate candidate) {
        if (candidate == null) return false;
        if (candidate.identity().isPremium()) {
            return true;
        }
        return candidate.version() != null && (UpdateSource.isPaidSource(candidate.version().sourceId()) || !candidate.version().downloadable());
    }

    public static @NotNull String applyPlaceholders(final @NotNull String template, final @NotNull Map<String, String> values) {
        String result = template;
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            final String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{" + entry.getKey() + "}", value)
                    .replace("%" + entry.getKey() + "%", value);
        }
        return result;
    }
}
