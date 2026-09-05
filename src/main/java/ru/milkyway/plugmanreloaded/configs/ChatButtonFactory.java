package ru.milkyway.plugmanreloaded.configs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.HexColors;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChatButtonFactory {

    private record RenderCtx(
            FileConfiguration config,
            Map<String, String> placeholders,
            String actionKey,
            String cmdType,
            String pluginName,
            String tokenSuffix,
            boolean isEnabled
    ) {
        private RenderCtx {
            if (placeholders == null) placeholders = Map.of();
        }
    }

    private enum ButtonToken {

        DEPS_BUTTON("{deps-button}") {
            @Override
            Component render(RenderCtx ctx) {
                String command = ctx.cmdType().equals("download")
                        ? "/plm download " + ctx.pluginName() + " confirm" + ctx.tokenSuffix()
                        : "/plm " + ctx.cmdType() + " " + ctx.pluginName() + (ctx.cmdType().equals("update") ? " -y" : " -c") + ctx.tokenSuffix();
                return buildCommandButton(ctx, ctx.cmdType(), "deps", null, command);
            }
        },

        SINGLE_BUTTON("{single-button}") {
            @Override
            Component render(RenderCtx ctx) {
                String command;
                if (ctx.cmdType().equals("delete")) {
                    command = "/plm delete " + ctx.pluginName() + " -y" + ctx.tokenSuffix();
                } else if (ctx.cmdType().equals("download")) {
                    command = "/plm download " + ctx.pluginName() + " confirm" + ctx.tokenSuffix();
                } else if (ctx.cmdType().equals("update")) {
                    boolean isDeps = ctx.actionKey() != null && ctx.actionKey().contains("dependents");
                    command = "/plm update " + ctx.pluginName() + (isDeps ? " -y -single" : " -y") + ctx.tokenSuffix();
                } else {
                    command = "/plm " + (ctx.cmdType().equals("safe-mode") ? "safe-mode" : ctx.cmdType()) + " " + ctx.pluginName() + " -f" + ctx.tokenSuffix();
                }
                return buildCommandButton(ctx, ctx.cmdType(), "single", null, command);
            }
        },

        CONFIRM_BUTTON("{confirm-button}") {
            @Override
            Component render(RenderCtx ctx) {
                String command = ctx.cmdType().equals("download")
                        ? "/plm download " + ctx.pluginName() + " confirm" + ctx.tokenSuffix()
                        : "/plm " + ctx.cmdType() + " " + ctx.pluginName() + " -c" + ctx.tokenSuffix();
                return buildCommandButton(ctx, ctx.cmdType(), "confirm", null, command);
            }
        },

        CANCEL_BUTTON("{cancel-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, ctx.cmdType(), "cancel", null,
                        "/plm " + ctx.cmdType() + " cancel " + ctx.pluginName() + ctx.tokenSuffix());
            }
        },

        SET_SOURCE_BUTTON("{set-source-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, ctx.cmdType(), "set", "actions.update.manual-source.buttons.set",
                        "/plm update " + ctx.pluginName() + " source" + ctx.tokenSuffix());
            }
        },

        SOURCE_LINK("{source-link}") {
            @Override
            Component render(RenderCtx ctx) {
                String url = resolveClickableUrl(ctx.placeholders());
                if (url == null || url.isBlank()) {
                    String sourceName = ctx.placeholders().get("source");
                    return sourceName != null ? HexColors.translateToComponent(sourceName) : Component.empty();
                }
                String textPath = resolveButtonPath(ctx.config(), ctx.actionKey(), ctx.cmdType(), "source", "text", "actions.update.manual-source.buttons.source");
                String hoverPath = resolveButtonPath(ctx.config(), ctx.actionKey(), ctx.cmdType(), "source", "hover", "actions.update.manual-source.buttons.source");
                Component button = buildTranslatedButton(ctx.config(), textPath, hoverPath, ctx.placeholders());
                return button == null ? Component.empty() : button.clickEvent(ClickEvent.openUrl(url));
            }
        },

        URL_LINK("{url}") {
            @Override
            Component render(RenderCtx ctx) {
                String url = resolveClickableUrl(ctx.placeholders());
                if (url == null || url.isBlank()) {
                    return Component.empty();
                }
                String textPath = findButtonPath(ctx.config(), ctx.actionKey(), ctx.cmdType(), "url", "text");
                if (textPath == null) textPath = "actions." + ctx.cmdType() + ".buttons.url.text";
                if (!configContains(ctx.config(), textPath)) textPath = "actions.download.buttons.url.text";

                String hoverPath = findButtonPath(ctx.config(), ctx.actionKey(), ctx.cmdType(), "url", "hover");
                if (hoverPath == null) hoverPath = "actions." + ctx.cmdType() + ".buttons.url.hover";
                if (!configContains(ctx.config(), hoverPath)) hoverPath = "actions.download.buttons.url.hover";

                FileConfiguration config = ctx.config();
                String buttonText = configContains(config, textPath) ? config.getString(textPath) : "{url}";
                if (buttonText.equals(url) || buttonText.equals("{url}")) {
                    buttonText = "&#FFFF00&n" + url;
                }
                String hoverText = readConfigText(config, hoverPath);

                buttonText = applyPlaceholdersForButton(buttonText, ctx.placeholders());
                hoverText = applyPlaceholdersForButton(hoverText, ctx.placeholders());

                Component button = HexColors.translateToComponent(buttonText);
                if (hoverText != null && !hoverText.isBlank()) {
                    button = button.hoverEvent(HoverEvent.showText(HexColors.translateToComponent(hoverText)));
                }
                return button.clickEvent(ClickEvent.openUrl(url));
            }
        },

        DOWNLOAD_BUTTON("{download-button}") {
            @Override
            Component render(RenderCtx ctx) {
                String project = ctx.placeholders().getOrDefault("project", ctx.pluginName());
                return buildCommandButton(ctx, "download", "download", "actions.download.buttons.download",
                        "/plm download " + project + " -y");
            }
        },

        INFO_BUTTON("{info-button}") {
            @Override
            Component render(RenderCtx ctx) {
                String textPath = resolveButtonPath(ctx.config(), ctx.actionKey(), "download", "info", "text", "actions.download.buttons.info");
                String hoverPath = resolveButtonPath(ctx.config(), ctx.actionKey(), "download", "info", "hover", "actions.download.buttons.info");
                Component button = buildTranslatedButton(ctx.config(), textPath, hoverPath, ctx.placeholders());
                if (button == null) return Component.empty();
                String url = resolveClickableUrl(ctx.placeholders());
                return (url != null && !url.isBlank()) ? button.clickEvent(ClickEvent.openUrl(url)) : button;
            }
        },

        WEB_BUTTON("{web-button}") {
            @Override
            Component render(RenderCtx ctx) {
                String url = resolveClickableUrl(ctx.placeholders());
                if (url == null || url.isBlank()) return Component.empty();
                String textPath = resolveButtonPath(ctx.config(), ctx.actionKey(), "download", "web", "text", "actions.download.buttons.web");
                String hoverPath = resolveButtonPath(ctx.config(), ctx.actionKey(), "download", "web", "hover", "actions.download.buttons.web");
                Component button = buildTranslatedButton(ctx.config(), textPath, hoverPath, ctx.placeholders());
                return button == null ? Component.empty() : button.clickEvent(ClickEvent.openUrl(url));
            }
        },

        CONFIRM_DOWNLOAD_BUTTON("{confirm-download-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "download", "confirm-single", "actions.download.buttons.confirm-single",
                        "/plm download " + ctx.pluginName() + " confirm" + ctx.tokenSuffix());
            }
        },

        CONFIRM_DOWNLOAD_DEPS_BUTTON("{confirm-download-deps-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "download", "confirm-deps", "actions.download.buttons.confirm-deps",
                        "/plm download " + ctx.pluginName() + " confirm" + ctx.tokenSuffix());
            }
        },

        DELETE_BUTTON("{delete-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "delete", "delete", "actions.info.buttons.delete",
                        "/plm delete " + ctx.pluginName());
            }
        },

        RELOAD_BUTTON("{reload-button}") {
            @Override
            Component render(RenderCtx ctx) {
                if (!ctx.isEnabled()) return Component.empty();
                return buildCommandButton(ctx, "info", "reload", "actions.info.buttons.reload",
                        "/plm reload " + ctx.pluginName());
            }
        },

        RESTART_BUTTON("{restart-button}") {
            @Override
            Component render(RenderCtx ctx) {
                if (!ctx.isEnabled()) return Component.empty();
                return buildCommandButton(ctx, "info", "restart", "actions.info.buttons.restart",
                        "/plm restart " + ctx.pluginName());
            }
        },

        CASCADE_RELOAD_BUTTON("{cascade-reload-button}") {
            @Override
            Component render(RenderCtx ctx) {
                if (!ctx.isEnabled()) return Component.empty();
                return buildCommandButton(ctx, "info", "cascade", "actions.info.buttons.cascade",
                        "/plm reload " + ctx.pluginName() + " -c");
            }
        },

        CASCADE_RESTART_BUTTON("{cascade-restart-button}") {
            @Override
            Component render(RenderCtx ctx) {
                if (!ctx.isEnabled()) return Component.empty();
                return buildCommandButton(ctx, "info", "cascade-restart", "actions.info.buttons.cascade-restart",
                        "/plm restart " + ctx.pluginName() + " -c");
            }
        },

        ENABLE_BUTTON("{enable-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "info", "enable", "actions.info.buttons.enable",
                        "/plm enable " + ctx.pluginName());
            }
        },

        DISABLE_BUTTON("{disable-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "info", "disable", "actions.info.buttons.disable",
                        "/plm disable " + ctx.pluginName());
            }
        },

        TOGGLE_BUTTON("{toggle-button}") {
            @Override
            Component render(RenderCtx ctx) {
                boolean isUnloaded = "true".equalsIgnoreCase(ctx.placeholders().get("is-unloaded"));
                String key = isUnloaded ? "load" : (ctx.isEnabled() ? "disable" : "enable");
                return buildCommandButton(ctx, "info", key, "actions.info.buttons." + key,
                        "/plm " + key + " " + ctx.pluginName());
            }
        },

        UNLOAD_BUTTON("{unload-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "info", "unload", "actions.info.buttons.unload",
                        "/plm unload " + ctx.pluginName());
            }
        },

        LOAD_BUTTON("{load-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "info", "load", "actions.info.buttons.load",
                        "/plm load " + ctx.pluginName());
            }
        },

        ALL_BUTTON("{all-button}") {
            @Override
            Component render(RenderCtx ctx) {
                return buildCommandButton(ctx, "update", "all", "actions.update.confirm-all-buttons.all",
                        "/plm update -all -y" + ctx.tokenSuffix());
            }
        },

        UPDATE_ALL_BUTTON("{update-all-button}") {
            @Override
            Component render(RenderCtx ctx) {
                String availableStr = ctx.placeholders().get("available");
                if (availableStr != null && (availableStr.equals("0") || availableStr.isEmpty())) {
                    return Component.empty();
                }
                return buildCommandButton(ctx, "update", "update-all", "actions.update.summary-buttons.update-all",
                        "/plm update -all");
            }
        };

        private final String token;

        ButtonToken(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }

        abstract Component render(RenderCtx ctx);
    }

    private static final Map<String, ButtonToken> TOKEN_MAP;
    static final String[] BUTTON_TOKENS;

    static {
        Map<String, ButtonToken> map = new LinkedHashMap<>();
        List<String> tokens = new ArrayList<>();
        for (ButtonToken bt : ButtonToken.values()) {
            map.put(bt.token(), bt);
            tokens.add(bt.token());
        }
        tokens.add("{buttons}");
        TOKEN_MAP = Collections.unmodifiableMap(map);
        BUTTON_TOKENS = tokens.toArray(new String[0]);
    }

    private ChatButtonFactory() {
    }

    static boolean hasButtonToken(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String token : BUTTON_TOKENS) {
            if (text.contains(token)) return true;
        }
        return false;
    }

    static Component renderInteractiveLine(FileConfiguration config, String line, Map<String, String> placeholders, String actionKey) {
        Component message = Component.empty();
        String remaining = line;

        while (true) {
            int earliestIdx = -1;
            String earliestToken = null;

            for (String token : BUTTON_TOKENS) {
                int idx = remaining.indexOf(token);
                if (idx != -1 && (earliestIdx == -1 || idx < earliestIdx)) {
                    earliestIdx = idx;
                    earliestToken = token;
                }
            }

            if (earliestIdx == -1) {
                if (!remaining.isEmpty()) {
                    message = message.append(HexColors.translateToComponent(remaining));
                }
                break;
            }

            if (earliestIdx > 0) {
                String before = remaining.substring(0, earliestIdx);
                message = message.append(HexColors.translateToComponent(before));
            }

            if (earliestToken.equals("{buttons}")) {
                message = message.append(createButton(config, "{reload-button}", placeholders, actionKey))
                        .append(Component.text("  "))
                        .append(createButton(config, "{cascade-reload-button}", placeholders, actionKey))
                        .append(Component.text("  "))
                        .append(createButton(config, "{toggle-button}", placeholders, actionKey))
                        .append(Component.text("  "))
                        .append(createButton(config, "{unload-button}", placeholders, actionKey));
            } else {
                Component button = createButton(config, earliestToken, placeholders, actionKey);
                message = message.append(button);
            }

            remaining = remaining.substring(earliestIdx + earliestToken.length());
        }

        return message;
    }

    static Component createButton(FileConfiguration config, String token, Map<String, String> placeholders, String actionKey) {
        ButtonToken buttonToken = TOKEN_MAP.get(token);
        if (buttonToken == null) {
            return Component.empty();
        }

        String pluginName = placeholders != null ? placeholders.getOrDefault("plugin", "") : "";
        boolean isEnabled = placeholders == null || !"false".equalsIgnoreCase(placeholders.get("is-enabled"));

        String tokenStr = placeholders != null ? placeholders.getOrDefault("token", "") : "";
        String tokenSuffix = tokenStr.isEmpty() ? "" : " " + tokenStr;

        String effectiveActionKey = actionKey != null && !actionKey.isBlank() ? actionKey : (placeholders != null ? placeholders.get("action-key") : null);
        String defaultCmd = "reload";
        if (effectiveActionKey != null) {
            String clean = effectiveActionKey.startsWith("actions.") ? effectiveActionKey.substring(8) : effectiveActionKey;
            int dot = clean.indexOf('.');
            if (dot > 0) defaultCmd = clean.substring(0, dot);
        }
        String cmdType = placeholders != null ? placeholders.getOrDefault("cmd-type", defaultCmd) : defaultCmd;

        RenderCtx ctx = new RenderCtx(config, placeholders, effectiveActionKey, cmdType, pluginName, tokenSuffix, isEnabled);
        return buttonToken.render(ctx);
    }

    private static Component buildCommandButton(RenderCtx ctx, String cmdType, String buttonKey, @Nullable String fallbackPrefix, String command) {
        String textPath = resolveButtonPath(ctx.config(), ctx.actionKey(), cmdType, buttonKey, "text", fallbackPrefix);
        String hoverPath = resolveButtonPath(ctx.config(), ctx.actionKey(), cmdType, buttonKey, "hover", fallbackPrefix);
        return buildCommandButton(ctx, textPath, hoverPath, command);
    }

    private static Component buildCommandButton(RenderCtx ctx, String textPath, String hoverPath, String command) {
        Component button = buildTranslatedButton(ctx.config(), textPath, hoverPath, ctx.placeholders());
        if (button == null) {
            return Component.empty();
        }
        if (command != null && !command.isBlank()) {
            button = button.clickEvent(ClickEvent.runCommand(command));
        }
        return button;
    }

    private static @Nullable Component buildTranslatedButton(FileConfiguration config, String textPath, String hoverPath, Map<String, String> placeholders) {
        if (!configContains(config, textPath)) {
            return null;
        }
        String buttonText = applyPlaceholdersForButton(config.getString(textPath), placeholders);
        String hoverText = applyPlaceholdersForButton(readConfigText(config, hoverPath), placeholders);

        Component button = HexColors.translateToComponent(buttonText);
        if (hoverText != null && !hoverText.isBlank()) {
            Component hoverComponent = HexColors.translateToComponent(hoverText);
            button = button.hoverEvent(HoverEvent.showText(hoverComponent));
        }
        return button;
    }

    private static @Nullable String readConfigText(@Nullable FileConfiguration config, @Nullable String path) {
        if (config == null || path == null || !config.contains(path)) return null;
        return config.isList(path) ? String.join("\n", config.getStringList(path)) : config.getString(path);
    }

    private static boolean configContains(FileConfiguration config, String path) {
        return config != null && path != null && config.contains(path);
    }

    private static @Nullable String resolveClickableUrl(@Nullable Map<String, String> placeholders) {
        if (placeholders == null) return null;
        String raw = placeholders.get("raw-url");
        String candidate = (raw != null && !raw.isBlank()) ? raw : placeholders.get("url");
        if (candidate == null || candidate.isBlank()) return null;
        if (candidate.indexOf('<') >= 0 || candidate.indexOf('{') >= 0) {
            Log.debug("chatbuttonfactory.url-looks-like-markup", "value", candidate);
            return null;
        }
        return candidate;
    }

    private static @Nullable String resolveButtonPath(@Nullable FileConfiguration config, String actionKey, String cmdType, String buttonKey, String suffix, @Nullable String fallbackPrefix) {
        String path = findButtonPath(config, actionKey, cmdType, buttonKey, suffix);
        if (path == null && fallbackPrefix != null) {
            return fallbackPrefix + "." + suffix;
        }
        return path;
    }

    private static List<String> getButtonKeyAliases(String buttonKey) {
        return switch (buttonKey) {
            case "all" -> List.of("all", "update-all", "confirm-all");
            case "update-all" -> List.of("update-all", "all", "confirm-all");
            case "single" -> List.of("single", "confirm-single", "confirm");
            case "deps" -> List.of("deps", "confirm-deps", "cascade");
            case "set" -> List.of("set", "source", "set-source");
            case "cancel" -> List.of("cancel", "cancelled", "canceled");
            default -> List.of(buttonKey);
        };
    }

    private static @Nullable String findButtonPath(@Nullable FileConfiguration config, String actionKey, String cmdType, String buttonKey, String suffix) {
        if (config == null) return null;
        List<String> buttonKeys = getButtonKeyAliases(buttonKey);

        for (String bKey : buttonKeys) {
            if (actionKey != null && !actionKey.isBlank()) {
                String clean = actionKey.startsWith("actions.") ? actionKey.substring(8) : actionKey;
                String[] specific = new String[]{
                        "actions." + clean + "-buttons." + bKey + "." + suffix,
                        "actions." + clean + ".buttons." + bKey + "." + suffix,
                        "actions." + clean + ".confirm-buttons." + bKey + "." + suffix,
                        "actions." + clean + "." + bKey + "." + suffix,
                        clean + "-buttons." + bKey + "." + suffix,
                        clean + ".buttons." + bKey + "." + suffix
                };
                for (String path : specific) {
                    if (config.contains(path)) return path;
                }
            }
            if (cmdType != null && !cmdType.isBlank()) {
                String[] cmdSpecific = new String[]{
                        "actions." + cmdType + ".confirm-all-buttons." + bKey + "." + suffix,
                        "actions." + cmdType + ".confirm-buttons." + bKey + "." + suffix,
                        "actions." + cmdType + ".summary-buttons." + bKey + "." + suffix,
                        "actions." + cmdType + ".buttons." + bKey + "." + suffix,
                        "actions." + cmdType + ".manual-source.buttons." + bKey + "." + suffix
                };
                for (String path : cmdSpecific) {
                    if (config.contains(path)) return path;
                }
            }
            String fallback = "actions.reload.confirm-buttons." + bKey + "." + suffix;
            if (config.contains(fallback)) return fallback;
        }
        return null;
    }

    private static String applyPlaceholdersForButton(@Nullable String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null || placeholders.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue() != null ? entry.getValue() : "";
            if (PluginMetaHelper.isVersionKey(key)) {
                val = PluginMetaHelper.cleanVersion(val);
            }
            result = result.replace("{" + key + "}", val).replace("%" + key + "%", val);
        }
        return PluginMetaHelper.cleanupDoubleV(result);
    }
}
