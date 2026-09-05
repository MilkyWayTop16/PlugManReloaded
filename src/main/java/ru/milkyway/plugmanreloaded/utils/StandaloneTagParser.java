package ru.milkyway.plugmanreloaded.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

final class StandaloneTagParser {

    private StandaloneTagParser() {}

    private static final class State {
        final Deque<TextColor> colors = new ArrayDeque<>();
        final Set<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
        final Deque<ClickEvent> clicks = new ArrayDeque<>();
        final Deque<HoverEvent<?>> hovers = new ArrayDeque<>();
    }

    static Component parse(String message) {
        if (message == null || message.isEmpty()) return Component.empty();
        String pre = HexColors.preprocess(message, false);
        if (pre.indexOf('<') < 0 && pre.indexOf('>') < 0) {
            return HexColors.SECTION_HEX.deserialize(HexColors.convert(pre, false));
        }

        TextComponent.Builder root = Component.text();
        State style = new State();
        int last = 0;
        int len = pre.length();
        int i = 0;

        while (i < len) {
            int tagStart = pre.indexOf('<', i);
            if (tagStart == -1) break;
            int tagEnd = HexColors.findTagEnd(pre, tagStart);
            if (tagEnd == -1) {
                i = tagStart + 1;
                continue;
            }

            boolean escaped = tagStart > 0 && pre.charAt(tagStart - 1) == '\\';
            int textEnd = escaped ? tagStart - 1 : tagStart;

            if (textEnd > last) {
                appendText(root, pre.substring(last, textEnd), style);
            }

            String tagContent = pre.substring(tagStart + 1, tagEnd);
            if (escaped) {
                appendText(root, "<" + tagContent + ">", style);
            } else {
                int colon = tagContent.indexOf(':');
                String name = (colon == -1 ? tagContent : tagContent.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
                String param = colon == -1 ? null : tagContent.substring(colon + 1);
                if (!handleTag(name, param, style, root)) {
                    appendText(root, "<" + tagContent + ">", style);
                }
            }

            last = tagEnd + 1;
            i = last;
        }

        if (last < len) {
            appendText(root, pre.substring(last), style);
        }
        return root.build();
    }

    private static void appendText(TextComponent.Builder root, String text, State style) {
        if (text.isEmpty()) return;
        TextComponent.Builder b = Component.text().append(HexColors.SECTION_HEX.deserialize(HexColors.convert(text, false)));
        TextColor color = style.colors.peek();
        if (color != null) b.color(color);
        for (TextDecoration d : style.decorations) b.decoration(d, true);
        ClickEvent click = style.clicks.peek();
        if (click != null) b.clickEvent(click);
        HoverEvent<?> hover = style.hovers.peek();
        if (hover != null) b.hoverEvent(hover);
        root.append(b.build());
    }

    private static boolean handleTag(String tag, String param, State style, TextComponent.Builder root) {
        boolean closing = tag.startsWith("/") || tag.startsWith("!");
        String bare = closing ? tag.substring(1) : tag;

        TextDecoration decoration = HexColors.DECORATIONS.get(bare);
        if (decoration != null) {
            if (closing) style.decorations.remove(decoration);
            else style.decorations.add(decoration);
            return true;
        }

        if (bare.startsWith("#") || bare.equals("color") || bare.equals("colour") || HexColors.namedColor(bare) != null) {
            if (closing) {
                if (!style.colors.isEmpty()) style.colors.pop();
                return true;
            }
            TextColor color = HexColors.parseColorToken(bare.startsWith("#") || param == null ? bare : param);
            if (color == null) return false;
            style.colors.push(color);
            return true;
        }

        switch (bare) {
            case "reset", "r", "clear" -> {
                style.colors.clear();
                style.decorations.clear();
                return true;
            }
            case "newline", "br" -> {
                root.append(Component.newline());
                return true;
            }
            case "click" -> {
                if (closing) {
                    if (!style.clicks.isEmpty()) style.clicks.pop();
                    return true;
                }
                ClickEvent event = parseClick(param);
                if (event == null) return false;
                style.clicks.push(event);
                return true;
            }
            case "hover" -> {
                if (closing) {
                    if (!style.hovers.isEmpty()) style.hovers.pop();
                    return true;
                }
                HoverEvent<?> event = parseHover(param);
                if (event == null) return false;
                style.hovers.push(event);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static @Nullable ClickEvent parseClick(@Nullable String param) {
        if (param == null) return null;
        String[] parts = param.split(":", 2);
        if (parts.length != 2) return null;
        String value = HexColors.unquote(parts[1].trim());
        return switch (parts[0].trim().toLowerCase(Locale.ROOT)) {
            case "run_command" -> ClickEvent.runCommand(value);
            case "suggest_command" -> ClickEvent.suggestCommand(value);
            case "open_url" -> ClickEvent.openUrl(value);
            case "copy_to_clipboard" -> ClickEvent.copyToClipboard(value);
            case "change_page" -> {
                try {
                    yield ClickEvent.changePage(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    Log.debug("standalonetagparser.page-number-parse-failed", "value", value);
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static @Nullable HoverEvent<?> parseHover(@Nullable String param) {
        if (param == null) return null;
        String[] parts = param.split(":", 2);
        if (parts.length != 2 || !parts[0].trim().equalsIgnoreCase("show_text")) return null;
        return HoverEvent.showText(HexColors.translateToComponent(HexColors.unquote(parts[1].trim()).replace("<newline>", "\n")));
    }
}

