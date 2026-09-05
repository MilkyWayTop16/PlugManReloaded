package ru.milkyway.plugmanreloaded.utils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HexColors {

    private static final AtomicBoolean MINI_FAILURE_REPORTED = new AtomicBoolean();
    private static final MiniMessage MINI_MESSAGE = createMiniMessage();

    static final LegacyComponentSerializer SECTION_HEX = LegacyComponentSerializer.builder()
            .character('\u00a7').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static final Cache<String, String> TRANSLATE_CACHE =
            CacheBuilder.newBuilder().maximumSize(2000).build();

    static final Pattern TAG_PATTERN = Pattern.compile("<(/?[a-zA-Z0-9_!#-]+)(?::([^>]*))?>");
    private static final Pattern GRADIENT_PATTERN = Pattern.compile(
            "(?i)<(?:gradient|g):([^>]+)>(.*?)</(?:gradient|g)>|\\{#([0-9a-fA-F]{6})>}(.*?)\\{#([0-9a-fA-F]{6})<}");
    private static final Pattern RAINBOW_PATTERN = Pattern.compile("(?i)<rainbow(?::([^>]+))?>(.*?)</rainbow>");
    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "(?i)[&\u00a7][0-9a-fk-orx]|(?i)[&\u00a7]x([&\u00a7][0-9a-fA-F]){6}|(?i)&#[0-9a-fA-F]{6}|(?i)\\{#[0-9a-fA-F]{6}\\}|(?i)\\[#[0-9a-fA-F]{6}\\]|<[^>]*>");
    private static final Pattern STRIP_TAGS_PATTERN = Pattern.compile("<[^>]*>");

    private static final String LEGACY_CODES = "0123456789abcdefklmnor";
    private static final String[] LEGACY_TAGS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "obfuscated", "bold", "strikethrough", "underlined", "italic", "reset"
    };

    static final Map<String, TextDecoration> DECORATIONS = Map.ofEntries(
            Map.entry("bold", TextDecoration.BOLD),
            Map.entry("b", TextDecoration.BOLD),
            Map.entry("italic", TextDecoration.ITALIC),
            Map.entry("i", TextDecoration.ITALIC),
            Map.entry("em", TextDecoration.ITALIC),
            Map.entry("underlined", TextDecoration.UNDERLINED),
            Map.entry("u", TextDecoration.UNDERLINED),
            Map.entry("strikethrough", TextDecoration.STRIKETHROUGH),
            Map.entry("st", TextDecoration.STRIKETHROUGH),
            Map.entry("s", TextDecoration.STRIKETHROUGH),
            Map.entry("obfuscated", TextDecoration.OBFUSCATED),
            Map.entry("obf", TextDecoration.OBFUSCATED)
    );

    private HexColors() {}

    private static @Nullable MiniMessage createMiniMessage() {
        try {
            if (Bukkit.getBukkitVersion().startsWith("1.16")) return null;
            return MiniMessage.miniMessage();
        } catch (Exception t) {
            return null;
        }
    }

    private static void reportMiniFailure(String stage, Throwable t) {
        if (MINI_FAILURE_REPORTED.compareAndSet(false, true)) {
            Log.warn("hexcolors.minimessage-failed", "stage", stage, "error", String.valueOf(t));
        }
    }

    public static String translate(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        String cached = TRANSLATE_CACHE.getIfPresent(text);
        if (cached != null) return cached;
        String result = convert(preprocess(text, false), false);
        TRANSLATE_CACHE.put(text, result);
        return result;
    }

    public static String toMiniMessage(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        return convert(text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<newline>"), true);
    }

    public static Component translateToComponent(@Nullable String message) {
        if (message == null || message.isEmpty()) return Component.empty();
        if (MINI_MESSAGE != null) {
            try {
                return MINI_MESSAGE.deserialize(convert(preprocess(message, true), true));
            } catch (Exception t) {
                reportMiniFailure("translateToComponent", t);
            }
        }
        return StandaloneTagParser.parse(message);
    }

    public static Component translateForConsole(String message) {
        return translateToComponent(message);
    }

    public static String escapeTags(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        if (MINI_MESSAGE != null) {
            try {
                return MINI_MESSAGE.escapeTags(text);
            } catch (Exception t) {
                reportMiniFailure("escapeTags", t);
            }
        }
        return text.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    }

    public static String escapeLegacy(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("&", "\\&").replace("\u00a7", "\\\u00a7");
    }

    public static String stripColors(String text) {
        return text == null ? "" : STRIP_PATTERN.matcher(text).replaceAll("");
    }

    public static String stripTags(String text) {
        return text == null ? "" : STRIP_TAGS_PATTERN.matcher(text).replaceAll("");
    }

    public static String toPlainText(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String toLegacy(Component component) {
        return component == null ? "" : SECTION_HEX.serialize(component);
    }

    public static Component fromLegacy(String legacy) {
        return legacy == null || legacy.isEmpty() ? Component.empty() : SECTION_HEX.deserialize(legacy);
    }

    private static boolean isHex6(String s) {
        if (s == null || s.length() != 6) return false;
        for (int i = 0; i < 6; i++) {
            if (!isHexChar(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static TextColor namedColor(String name) {
        if (name == null || name.isEmpty()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        TextColor color = NamedTextColor.NAMES.value(key);
        if (color != null) return color;
        if (key.equals("grey")) return NamedTextColor.GRAY;
        if (key.equals("dark_grey")) return NamedTextColor.DARK_GRAY;
        return null;
    }

    static TextColor parseColorToken(String raw) {
        if (raw == null) return null;
        String value = unquote(raw.trim());
        if (value.startsWith("#") && isHex6(value.substring(1))) return TextColor.fromHexString(value);
        if (isHex6(value)) return TextColor.fromHexString("#" + value);
        return namedColor(value);
    }

    static String preprocess(String input, boolean mini) {
        if (input == null || input.isEmpty()) return "";
        String result = renderGradients(input, mini);
        return renderRainbows(result, mini);
    }

    private static String renderGradients(String input, boolean mini) {
        Matcher m = GRADIENT_PATTERN.matcher(input);
        if (!m.find()) return input;
        StringBuffer sb = new StringBuffer();
        do {
            List<Color> stops;
            String content;
            if (m.group(1) != null) {
                stops = parseColorStops(m.group(1));
                content = m.group(2);
            } else {
                stops = List.of(toAwt(m.group(3)), toAwt(m.group(5)));
                content = m.group(4);
            }
            String rendered = stops.size() >= 2 ? applyGradient(content, stops, mini) : content;
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderRainbows(String input, boolean mini) {
        Matcher m = RAINBOW_PATTERN.matcher(input);
        if (!m.find()) return input;
        StringBuffer sb = new StringBuffer();
        do {
            String arg = m.group(1);
            boolean reversed = arg != null && arg.contains("!");
            int phase = 0;
            if (arg != null) {
                String clean = arg.replace("!", "").trim();
                if (!clean.isEmpty()) {
                    try {
                        phase = (int) (Float.parseFloat(clean) * 10);
                    } catch (NumberFormatException e) {
                        Log.debug("hexcolors.rainbow-phase-parse-failed", "phase", clean);
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(applyRainbow(m.group(2), phase, reversed, mini)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<Color> parseColorStops(String args) {
        List<Color> list = new ArrayList<>();
        if (args == null || args.isEmpty()) return list;
        for (String part : args.split(":")) {
            TextColor color = parseColorToken(part);
            if (color != null) list.add(new Color(color.value()));
        }
        return list;
    }

    private static Color toAwt(String hex) {
        return isHex6(hex) ? new Color(Integer.parseInt(hex, 16)) : Color.WHITE;
    }

    private static String applyGradient(String text, List<Color> colors, boolean mini) {
        if (text == null || text.isEmpty()) return "";
        if (colors == null || colors.size() < 2) return text;
        StringBuilder sb = new StringBuilder(text.length() * 16);
        int len = text.length();
        int segments = colors.size() - 1;
        for (int i = 0; i < len; i++) {
            float pos = len > 1 ? (float) i / (len - 1) * segments : 0f;
            int idx = Math.min((int) pos, segments - 1);
            float progress = pos - idx;
            Color a = colors.get(idx);
            Color b = colors.get(idx + 1);
            appendColored(sb, String.format("%02x%02x%02x",
                    (int) (a.getRed() + progress * (b.getRed() - a.getRed())),
                    (int) (a.getGreen() + progress * (b.getGreen() - a.getGreen())),
                    (int) (a.getBlue() + progress * (b.getBlue() - a.getBlue()))), text.charAt(i), mini);
        }
        return sb.toString();
    }

    private static String applyRainbow(String text, int phase, boolean reversed, boolean mini) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length() * 16);
        int len = text.length();
        for (int i = 0; i < len; i++) {
            float hue = (float) (i + phase) / Math.max(1, len);
            if (reversed) hue = 1.0f - hue;
            hue = hue - (float) Math.floor(hue);
            int rgb = Color.HSBtoRGB(hue, 1f, 1f);
            appendColored(sb, String.format("%02x%02x%02x",
                    (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF), text.charAt(i), mini);
        }
        return sb.toString();
    }

    private static void appendColored(StringBuilder sb, String hex, char c, boolean mini) {
        appendHex(sb, hex, mini);
        sb.append(c);
    }

    private static void appendHex(StringBuilder out, String hex, boolean mini) {
        String lower = hex.toLowerCase(Locale.ROOT);
        if (mini) {
            out.append("<#").append(lower).append('>');
            return;
        }
        out.append('\u00a7').append('x');
        for (int i = 0; i < 6; i++) out.append('\u00a7').append(lower.charAt(i));
    }

    private static void appendLegacy(StringBuilder out, char code) {
        int idx = LEGACY_CODES.indexOf(Character.toLowerCase(code));
        if (idx < 0) {
            out.append('&').append(code);
            return;
        }
        out.append('<').append(LEGACY_TAGS[idx]).append('>');
    }

    static String convert(String in, boolean mini) {
        if (in == null || in.isEmpty()) return "";
        StringBuilder out = new StringBuilder(in.length() + 32);
        int i = 0;
        int n = in.length();
        while (i < n) {
            char c = in.charAt(i);
            if (c == '\\' && i + 1 < n && isEscapable(in.charAt(i + 1))) {
                out.append(in.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<' && mini) {
                int end = findTagEnd(in, i);
                if (end != -1) {
                    out.append(in, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            if ((c == '&' || c == '\u00a7') && i + 1 < n) {
                char next = in.charAt(i + 1);
                if (next == '#' && i + 7 < n && isHex6(in.substring(i + 2, i + 8))) {
                    appendHex(out, in.substring(i + 2, i + 8), mini);
                    i += 8;
                    continue;
                }
                if ((next == 'x' || next == 'X') && i + 13 < n) {
                    String xHex = extractXHex(in, i + 2);
                    if (xHex != null) {
                        appendHex(out, xHex, mini);
                        i += 14;
                        continue;
                    }
                }
                if (LEGACY_CODES.indexOf(Character.toLowerCase(next)) >= 0) {
                    if (mini) appendLegacy(out, next);
                    else out.append('\u00a7').append(Character.toLowerCase(next));
                    i += 2;
                    continue;
                }
            }
            if ((c == '{' || c == '[') && i + 8 < n && in.charAt(i + 1) == '#'
                    && (in.charAt(i + 8) == '}' || in.charAt(i + 8) == ']')
                    && isHex6(in.substring(i + 2, i + 8))) {
                appendHex(out, in.substring(i + 2, i + 8), mini);
                i += 9;
                continue;
            }
            if (c == '#' && i + 6 < n && isHex6(in.substring(i + 1, i + 7))
                    && (i == 0 || !isHexChar(in.charAt(i - 1)))) {
                appendHex(out, in.substring(i + 1, i + 7), mini);
                i += 7;
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isEscapable(char c) {
        return c == '\\' || c == '<' || c == '>' || c == '&' || c == '\u00a7';
    }

    private static String extractXHex(String in, int start) {
        if (start + 12 > in.length()) return null;
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            char mark = in.charAt(start + i * 2);
            char hex = in.charAt(start + i * 2 + 1);
            if ((mark != '&' && mark != '\u00a7') || !isHexChar(hex)) return null;
            sb.append(hex);
        }
        return sb.toString();
    }

    static int findTagEnd(String in, int start) {
        boolean inSingle = false;
        boolean inDouble = false;
        int limit = Math.min(in.length(), start + 2048);
        for (int i = start + 1; i < limit; i++) {
            char c = in.charAt(i);
            if (c == '\\' && (inSingle || inDouble)) {
                i++;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '>' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    static String unquote(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && ((t.charAt(0) == '\'' && t.endsWith("'")) || (t.charAt(0) == '"' && t.endsWith("\"")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}

