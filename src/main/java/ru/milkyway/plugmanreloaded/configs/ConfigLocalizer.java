package ru.milkyway.plugmanreloaded.configs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ConfigLocalizer {

    private ConfigLocalizer() {}

    private static final Map<String, String> TEMPLATES = Map.of(
            "ru", "config.yml",
            "en", "config-en.yml"
    );

    public static @Nullable String templateResourceFor(@Nullable String language) {
        if (language == null) return null;
        return TEMPLATES.get(language.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean apply(PlugManReloaded plugin, @Nullable File configFile, String language) {
        if (configFile == null || !configFile.isFile()) return false;

        String resource = templateResourceFor(language);
        if (resource == null) {
            Log.debug("configlocalizer.no-template", "language", String.valueOf(language));
            return false;
        }

        try {
            String templateText = readResource(plugin, resource);
            if (templateText == null || templateText.isBlank()) {
                Log.warn("configlocalizer.template-missing", "resource", resource);
                return false;
            }

            String rawCurrent = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            FileConfiguration user = YamlConfiguration.loadConfiguration(configFile);
            String generated = render(templateText, user);

            if (generated.equals(normalize(rawCurrent))) return false;
            if (!preservesEverything(user, generated)) return false;

            ConfigUpdater.createBackup(plugin, configFile);
            writeAtomically(configFile, rawCurrent.contains("\r\n") ? generated.replace("\n", "\r\n") : generated);
            if (plugin != null) {
                Log.info("configlocalizer.applied", "language", language, "file", configFile.getName());
            }
            return true;
        } catch (Throwable t) {
            Log.warn("configlocalizer.failed", t, "file", configFile.getName());
            return false;
        }
    }

    private static boolean preservesEverything(FileConfiguration user, String generated) {
        FileConfiguration rendered = YamlConfiguration.loadConfiguration(new StringReader(generated));
        for (String path : user.getKeys(true)) {
            if (user.isConfigurationSection(path)) continue;
            if (!rendered.contains(path)) {
                Log.warn("configlocalizer.key-would-be-lost", "key", path);
                return false;
            }
            if (!Objects.equals(user.get(path), rendered.get(path))) {
                Log.warn("configlocalizer.value-would-change", "key", path);
                return false;
            }
        }
        return true;
    }

    static String render(String templateText, FileConfiguration user) {
        FileConfiguration template = YamlConfiguration.loadConfiguration(new StringReader(templateText));
        List<String> lines = List.of(normalize(templateText).split("\n", -1));
        List<String> out = new ArrayList<>(lines.size());

        List<String> pathKeys = new ArrayList<>();
        List<Integer> pathIndents = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                out.add(line);
                i++;
                continue;
            }

            int colon = findKeyColon(trimmed);
            if (colon < 0) {
                out.add(line);
                i++;
                continue;
            }

            int indent = indentOf(line);
            String key = trimmed.substring(0, colon).trim();
            String rawValue = trimmed.substring(colon + 1).trim();

            while (!pathIndents.isEmpty() && pathIndents.get(pathIndents.size() - 1) >= indent) {
                pathIndents.remove(pathIndents.size() - 1);
                pathKeys.remove(pathKeys.size() - 1);
            }
            pathKeys.add(key);
            pathIndents.add(indent);
            String path = String.join(".", pathKeys);

            if (!rawValue.isEmpty()) {
                out.add(renderScalar(line, indent, key, path, template, user));
                i++;
                continue;
            }

            out.add(line);
            i++;

            int next = nextMeaningful(lines, i);
            boolean isList = next < lines.size()
                    && lines.get(next).trim().startsWith("-")
                    && indentOf(lines.get(next)) > indent;
            if (!isList) continue;

            List<String> templateItems = new ArrayList<>();
            while (i < lines.size()) {
                String candidate = lines.get(i);
                String candidateTrimmed = candidate.trim();
                if (!candidateTrimmed.startsWith("-") || indentOf(candidate) <= indent) break;
                templateItems.add(candidate);
                i++;
            }

            List<?> userList = user.contains(path) ? user.getList(path) : null;
            if (userList == null || Objects.equals(template.getList(path), userList)) {
                out.addAll(templateItems);
                continue;
            }

            String itemIndent = templateItems.isEmpty()
                    ? " ".repeat(indent + 2)
                    : " ".repeat(indentOf(templateItems.get(0)));
            for (Object item : userList) {
                out.add(itemIndent + "- " + serialize(item));
            }
        }

        return String.join("\n", out);
    }

    private static String renderScalar(String original, int indent, String key, String path,
                                       FileConfiguration template, FileConfiguration user) {
        if (!user.contains(path)) return original;

        Object userValue = user.get(path);
        if (userValue instanceof ConfigurationSection || userValue instanceof List || userValue instanceof Map) {
            return original;
        }
        if (Objects.equals(userValue, template.get(path))) return original;

        return " ".repeat(indent) + key + ": " + serialize(userValue);
    }

    private static String serialize(@Nullable Object value) {
        if (value == null) return "\"\"";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int findKeyColon(String trimmed) {
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '"' || c == '\'' || c == '#') return -1;
            if (c == ':' && (i + 1 == trimmed.length() || trimmed.charAt(i + 1) == ' ')) return i;
        }
        return -1;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static int nextMeaningful(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) return i;
        }
        return lines.size();
    }

    private static String readResource(PlugManReloaded plugin, String resource) throws Exception {
        InputStream stream = plugin != null ? plugin.getResource(resource) : null;
        if (stream == null) {
            stream = ConfigLocalizer.class.getClassLoader().getResourceAsStream(resource);
        }
        if (stream == null) return null;
        try (InputStream in = stream) {
            return normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static void writeAtomically(File file, String content) throws Exception {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.writeString(temp.toPath(), content, StandardCharsets.UTF_8);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception fallback) {
            Log.debug("configlocalizer.atomic-move-unsupported", fallback);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

