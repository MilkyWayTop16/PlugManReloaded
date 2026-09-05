package ru.milkyway.plugmanreloaded.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SourceCatalog {

    private static final String RESOURCE = "/plugin-sources.json";
    private static final String USER_RESOURCE_RU = "/sources-custom.yml";
    private static final String USER_RESOURCE_EN = "/sources-custom-en.yml";
    private static final String USER_FILE_NAME = "sources-custom.yml";

    private static final double MIN_NAME_SIMILARITY = 0.85;

    private static final List<String> OPTION_FIELDS =
            List.of("endpoint", "versionPath", "downloadPath", "url", "loaders", "gameVersions");

    public record CatalogSource(String sourceId, String ref, String url, Map<String, String> options) {}

    public record CatalogEntry(String pluginName, List<String> aliases, List<CatalogSource> sources) {}

    private final Map<String, List<CatalogEntry>> byMainClass;
    private final Map<String, UserEntry> userEntries;
    private final Set<String> reportedMismatches = ConcurrentHashMap.newKeySet();

    private record UserEntry(String mainClass, List<CatalogSource> sources) {}

    public SourceCatalog(File userCatalogFile) {
        this(userCatalogFile, "ru");
    }

    public SourceCatalog(File userCatalogFile, String language) {
        this.byMainClass = load();
        this.userEntries = loadUserCatalog(userCatalogFile, templateResourceFor(language));
    }

    public static File resolveFile(PlugManReloaded plugin) {
        return plugin != null && plugin.getDataFolder() != null ? new File(plugin.getDataFolder(), USER_FILE_NAME) : new File(USER_FILE_NAME);
    }

    private static String templateResourceFor(String language) {
        return "en".equalsIgnoreCase(language) ? USER_RESOURCE_EN : USER_RESOURCE_RU;
    }

    private static Map<String, List<CatalogEntry>> load() {
        Map<String, List<CatalogEntry>> map = new HashMap<>();

        try (InputStream stream = SourceCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                Log.debug("sourcecatalog.resource-not-found", "resource", RESOURCE);
                return Map.of();
            }

            JsonElement parsed = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonElement.class);
            if (!parsed.isJsonObject()) {
                return Map.of();
            }

            JsonArray plugins = parsed.getAsJsonObject().getAsJsonArray("plugins");
            if (plugins == null) {
                return Map.of();
            }

            for (JsonElement element : plugins) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();

                String main = text(entry, "main");
                if (main == null || main.isBlank()) continue;

                JsonArray sources = entry.getAsJsonArray("sources");
                if (sources == null || sources.size() == 0) continue;

                List<CatalogSource> parsedSources = new ArrayList<>();
                for (JsonElement raw : sources) {
                    if (!raw.isJsonObject()) continue;
                    JsonObject source = raw.getAsJsonObject();
                    String id = text(source, "id");
                    String ref = text(source, "ref");
                    if (id == null || ref == null) continue;
                    Map<String, String> options = new HashMap<>();
                    for (String field : OPTION_FIELDS) {
                        String value = text(source, field);
                        if (value != null) {
                            options.put(field, value);
                        }
                    }
                    parsedSources.add(new CatalogSource(id.toLowerCase(Locale.ROOT), ref,
                            text(source, "url"), Map.copyOf(options)));
                }

                if (!parsedSources.isEmpty()) {
                    List<String> aliases = new ArrayList<>();
                    JsonArray rawAliases = entry.getAsJsonArray("aliases");
                    if (rawAliases != null) {
                        for (JsonElement alias : rawAliases) {
                            if (alias.isJsonPrimitive()) {
                                aliases.add(alias.getAsString());
                            }
                        }
                    }
                    map.computeIfAbsent(key(main), unused -> new ArrayList<>())
                            .add(new CatalogEntry(text(entry, "name"), List.copyOf(aliases), List.copyOf(parsedSources)));
                }
            }

            Log.debug("sourcecatalog.loaded", "count", String.valueOf(map.size()));
            map.replaceAll((unused, entries) -> List.copyOf(entries));
        } catch (Exception t) {
            Log.warn("sourcecatalog.load-failed", t, "error", t.getMessage());
            return Map.of();
        }
        return Map.copyOf(map);
    }

    private static Map<String, UserEntry> loadUserCatalog(@Nullable File file, String templateResource) {
        if (file == null) {
            return Map.of();
        }

        if (!file.isFile() && !createUserCatalog(file, templateResource)) {
            return Map.of();
        }

        Map<String, UserEntry> result = new LinkedHashMap<>();
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection plugins = yaml.getConfigurationSection("plugins");
            if (plugins == null) {
                return Map.of();
            }

            for (String pluginName : plugins.getKeys(false)) {
                ConfigurationSection entry = plugins.getConfigurationSection(pluginName);
                if (entry == null) {
                    Log.warn("sourcecatalog.entry-no-params", "plugin", pluginName);
                    continue;
                }

                List<CatalogSource> sources = readUserSources(pluginName, entry);
                if (sources.isEmpty()) {
                    Log.warn("sourcecatalog.entry-no-valid-source", "plugin", pluginName);
                    continue;
                }

                UserEntry parsed = new UserEntry(entry.getString("main"), List.copyOf(sources));
                result.put(NameUtil.normalizeName(pluginName), parsed);
                for (String alias : entry.getStringList("aliases")) {
                    result.putIfAbsent(NameUtil.normalizeName(alias), parsed);
                }
            }

            if (!result.isEmpty()) {
                Log.info("sourcecatalog.custom-sources-loaded", "count", String.valueOf(result.size()));
            }
        } catch (Exception t) {
            Log.warn("sourcecatalog.custom-file-read-failed", t);
            return Map.of();
        }
        return Map.copyOf(result);
    }

    private static List<CatalogSource> readUserSources(String pluginName, ConfigurationSection entry) {
        List<CatalogSource> sources = new ArrayList<>();
        List<Map<?, ?>> rawSources = entry.getMapList("sources");

        for (Map<?, ?> raw : rawSources) {
            String id = string(raw, "id");
            String ref = string(raw, "ref");
            if (id == null || id.isBlank()) {
                Log.warn("sourcecatalog.source-missing-id", "plugin", pluginName);
                continue;
            }
            if (ref == null || ref.isBlank()) {
                ref = pluginName;
            }

            Map<String, String> options = new HashMap<>();
            for (String field : OPTION_FIELDS) {
                String value = string(raw, field);
                if (value != null) {
                    options.put(field, value);
                }
            }
            sources.add(new CatalogSource(id.trim().toLowerCase(Locale.ROOT), ref.trim(),
                    string(raw, "url"), Map.copyOf(options)));
        }
        return sources;
    }

    private static boolean createUserCatalog(File file, String templateResource) {
        String resolved = templateResource;
        if (SourceCatalog.class.getResource(resolved) == null && !USER_RESOURCE_RU.equals(resolved)) {
            resolved = USER_RESOURCE_RU;
        }
        try (InputStream stream = SourceCatalog.class.getResourceAsStream(resolved)) {
            if (stream == null) {
                Log.debug("sourcecatalog.template-not-found", "resource", resolved);
                return false;
            }
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.copy(stream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception t) {
            Log.warn("sourcecatalog.custom-file-create-failed", t, "file", file.getName());
            return false;
        }
    }

    public List<CatalogSource> lookup(String mainClass, String pluginName) {
        List<CatalogSource> own = lookupUser(mainClass, pluginName);
        if (own != null) {
            return own;
        }

        if (mainClass != null && !mainClass.isBlank()) {
            List<CatalogEntry> entries = byMainClass.getOrDefault(key(mainClass), List.of());
            if (!entries.isEmpty()) {
                String wanted = NameUtil.normalizeName(pluginName);
                for (CatalogEntry entry : entries) {
                    if (nameMatches(wanted, entry.pluginName())) {
                        return entry.sources();
                    }
                    for (String alias : entry.aliases()) {
                        if (nameMatches(wanted, alias)) {
                            return entry.sources();
                        }
                    }
                }

                if (reportedMismatches.add(key(mainClass) + "|" + wanted)) {
                    Log.debug("sourcecatalog.mainclass-mismatch", "mainClass", mainClass, "plugin", pluginName);
                }
                return List.of();
            }
        }

        return lookupByName(pluginName);
    }

    public List<CatalogSource> lookupByName(@Nullable String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return List.of();
        List<CatalogSource> user = lookupUser(null, pluginName);
        if (user != null && !user.isEmpty()) return user;

        String wanted = NameUtil.normalizeName(pluginName);
        for (List<CatalogEntry> list : byMainClass.values()) {
            for (CatalogEntry entry : list) {
                if (nameMatches(wanted, entry.pluginName())) {
                    return entry.sources();
                }
                for (String alias : entry.aliases()) {
                    if (nameMatches(wanted, alias)) {
                        return entry.sources();
                    }
                }
            }
        }
        return List.of();
    }

    private @Nullable List<CatalogSource> lookupUser(String mainClass, String pluginName) {
        if (userEntries.isEmpty() || pluginName == null || pluginName.isBlank()) {
            return null;
        }

        UserEntry entry = userEntries.get(NameUtil.normalizeName(pluginName));
        if (entry == null) {
            return null;
        }

        String required = entry.mainClass();
        if (required != null && !required.isBlank() && !key(required).equals(key(mainClass == null ? "" : mainClass))) {
            Log.debug("sourcecatalog.custom-mainclass-mismatch", "plugin", pluginName, "required", required, "installed", String.valueOf(mainClass));
            return null;
        }
        return entry.sources();
    }

    private static boolean nameMatches(@Nullable String wantedNormalized, String catalogName) {
        if (wantedNormalized == null || wantedNormalized.isEmpty() || catalogName == null) {
            return false;
        }
        String candidate = NameUtil.normalizeName(catalogName);
        if (wantedNormalized.equals(candidate)) {
            return true;
        }
        return NameUtil.similarity(wantedNormalized, candidate) >= MIN_NAME_SIMILARITY;
    }

    public @Nullable CatalogSource sourceFor(String mainClass, String pluginName, String sourceId) {
        for (CatalogSource source : lookup(mainClass, pluginName)) {
            if (source.sourceId().equalsIgnoreCase(sourceId)) {
                return source;
            }
        }
        return null;
    }

    private static String key(String mainClass) {
        return mainClass.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String string(Map<?, ?> map, String field) {
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }
}

