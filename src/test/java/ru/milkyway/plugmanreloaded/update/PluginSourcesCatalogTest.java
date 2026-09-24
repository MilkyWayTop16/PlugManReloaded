package ru.milkyway.plugmanreloaded.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSourcesCatalogTest {

    private static final double NAME_COLLISION_THRESHOLD = 0.85;

    private static final List<Class<?>> SOURCE_CLASSES = List.of(
            ru.milkyway.plugmanreloaded.update.source.ModrinthSource.class,
            ru.milkyway.plugmanreloaded.update.source.HangarSource.class,
            ru.milkyway.plugmanreloaded.update.source.GithubSource.class,
            ru.milkyway.plugmanreloaded.update.source.SpigotSource.class,
            ru.milkyway.plugmanreloaded.update.source.RusPigotSource.class,
            ru.milkyway.plugmanreloaded.update.source.DirectSource.class,
            ru.milkyway.plugmanreloaded.update.source.JenkinsSource.class
    );

    private static final Set<String> STRUCTURAL_SOURCE_KEYS = Set.of("id", "ref");
    private static final Set<String> ENTRY_KEYS = Set.of("main", "name", "aliases", "sources");

    private static final Map<String, String> REQUIRED_HOST = Map.of(
            "modrinth", "modrinth.com",
            "hangar", "hangar.papermc.io",
            "github", "github.com",
            "spigot", "spigotmc.org",
            "ruspigot", "ruspigot.ru"
    );

    private static final Map<String, Pattern> REF_SHAPE = Map.of(
            "spigot", Pattern.compile("\\d+"),
            "ruspigot", Pattern.compile("\\d+"),
            "github", Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"),
            "hangar", Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"),
            "modrinth", Pattern.compile("[A-Za-z0-9!@$().+_-]{3,64}")
    );

    private static final Map<String, Set<String>> REQUIRED_OPTIONS = Map.of(
            "direct", Set.of("endpoint", "downloadPath"),
            "jenkins", Set.of("endpoint")
    );

    private static JsonArray plugins() throws Exception {
        try (InputStream in = PluginSourcesCatalogTest.class.getClassLoader()
                .getResourceAsStream("plugin-sources.json")) {
            assertTrue(in != null, "plugin-sources.json отсутствует в ресурсах");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray array = root.getAsJsonArray("plugins");
            assertTrue(array != null && !array.isEmpty(), "в plugin-sources.json нет ни одной записи");
            return array;
        }
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Test
    void everyEntryReachesSourceCatalogIntact() throws Exception {
        SourceCatalog catalog = new SourceCatalog(null);
        List<String> problems = new ArrayList<>();

        for (JsonElement element : plugins()) {
            JsonObject entry = element.getAsJsonObject();
            String main = text(entry, "main");
            String name = text(entry, "name");
            String where = name + " (" + main + ")";

            List<SourceCatalog.CatalogSource> loaded = catalog.lookup(main, name);
            JsonArray declared = entry.getAsJsonArray("sources");

            if (loaded.size() != declared.size()) {
                problems.add(where + ": объявлено источников " + declared.size()
                        + ", загрузчик вернул " + loaded.size());
                continue;
            }

            for (int i = 0; i < declared.size(); i++) {
                JsonObject raw = declared.get(i).getAsJsonObject();
                SourceCatalog.CatalogSource source = loaded.get(i);
                String id = text(raw, "id");

                if (!id.equalsIgnoreCase(source.sourceId())) {
                    problems.add(where + ": источник " + id + " загрузился как " + source.sourceId());
                }
                if (!text(raw, "ref").equals(source.ref())) {
                    problems.add(where + "/" + id + ": ref искажён при загрузке");
                }
                for (String key : raw.keySet()) {
                    if (STRUCTURAL_SOURCE_KEYS.contains(key)) continue;
                    String expected = text(raw, key);
                    if (!expected.equals(source.options().get(key))) {
                        problems.add(where + "/" + id + ": поле «" + key
                                + "» не доходит до источника — его нет в OPTION_FIELDS");
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(), "Каталог теряет данные при загрузке:\n  " + String.join("\n  ", problems));
    }

    @Test
    void refsAndUrlsMatchTheirSource() throws Exception {
        List<String> problems = new ArrayList<>();

        for (JsonElement element : plugins()) {
            JsonObject entry = element.getAsJsonObject();
            String where = text(entry, "name");

            for (JsonElement rawElement : entry.getAsJsonArray("sources")) {
                JsonObject raw = rawElement.getAsJsonObject();
                String id = text(raw, "id");
                String ref = text(raw, "ref");
                String url = text(raw, "url");
                String at = where + "/" + id;

                Pattern shape = REF_SHAPE.get(id);
                if (shape != null && (ref == null || !shape.matcher(ref).matches())) {
                    problems.add(at + ": ref «" + ref + "» не той формы, ожидается " + shape.pattern());
                }

                for (String required : REQUIRED_OPTIONS.getOrDefault(id, Set.of())) {
                    if (text(raw, required) == null) {
                        problems.add(at + ": без поля «" + required + "» источник молча вернёт null");
                    }
                }

                if (url == null || !url.startsWith("https://")) {
                    problems.add(at + ": url должен быть абсолютным https-адресом");
                    continue;
                }

                String host = REQUIRED_HOST.get(id);
                if (host != null && !url.contains(host)) {
                    problems.add(at + ": url ведёт не на " + host + " — " + url);
                }
                if (("github".equals(id) || "hangar".equals(id) || "modrinth".equals(id))
                        && ref != null && !url.endsWith("/" + ref)) {
                    problems.add(at + ": url и ref расходятся — " + url + " против " + ref);
                }
                if (("spigot".equals(id) || "ruspigot".equals(id)) && ref != null && !url.contains(ref)) {
                    problems.add(at + ": в url нет номера ресурса " + ref);
                }
            }
        }

        assertTrue(problems.isEmpty(), "Ссылки каталога не сходятся:\n  " + String.join("\n  ", problems));
    }

    @Test
    void entriesUseOnlyImplementedSourcesAndFields() throws Exception {
        Set<String> implemented = implementedSourceIds();
        Set<String> readOptions = optionKeysReadByCode();
        Set<String> seen = new HashSet<>();
        List<String> problems = new ArrayList<>();

        for (JsonElement element : plugins()) {
            JsonObject entry = element.getAsJsonObject();
            String main = text(entry, "main");
            String name = text(entry, "name");
            String where = name + " (" + main + ")";

            if (main == null || !main.contains(".") || main.endsWith(".")) {
                problems.add(where + ": main-класс не похож на полное имя класса");
            }
            if (name == null || name.isBlank()) {
                problems.add(main + ": пустое имя");
            }
            if (!seen.add((main + "|" + name).toLowerCase(java.util.Locale.ROOT))) {
                problems.add(where + ": дубликат записи");
            }
            for (String key : entry.keySet()) {
                if (!ENTRY_KEYS.contains(key)) {
                    problems.add(where + ": неизвестное поле записи «" + key + "»");
                }
            }

            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement rawElement : entry.getAsJsonArray("sources")) {
                JsonObject raw = rawElement.getAsJsonObject();
                String id = text(raw, "id");
                if (!implemented.contains(id)) {
                    problems.add(where + ": источник «" + id + "» не реализован, известны " + implemented);
                }
                if (!ids.add(id)) {
                    problems.add(where + ": источник «" + id + "» указан дважды");
                }
                for (String key : raw.keySet()) {
                    if (STRUCTURAL_SOURCE_KEYS.contains(key) || readOptions.contains(key)) continue;
                    problems.add(where + "/" + id + ": поле «" + key + "» не читает ни один источник");
                }
            }
        }

        assertTrue(problems.isEmpty(), "Каталог не сходится с кодом:\n  " + String.join("\n  ", problems));
    }

    @Test
    void namesStayDistinguishableBetweenEntries() throws Exception {
        List<List<String>> perEntry = new ArrayList<>();
        for (JsonElement element : plugins()) {
            JsonObject entry = element.getAsJsonObject();
            List<String> names = new ArrayList<>();
            names.add(text(entry, "name"));
            JsonArray aliases = entry.getAsJsonArray("aliases");
            if (aliases != null) {
                for (JsonElement alias : aliases) {
                    names.add(alias.getAsString());
                }
            }
            perEntry.add(names);
        }

        List<String> problems = new ArrayList<>();
        for (int i = 0; i < perEntry.size(); i++) {
            for (int j = i + 1; j < perEntry.size(); j++) {
                for (String left : perEntry.get(i)) {
                    for (String right : perEntry.get(j)) {
                        double similarity = PluginMatcher.similarity(
                                PluginMatcher.normalizeName(left), PluginMatcher.normalizeName(right));
                        if (similarity >= NAME_COLLISION_THRESHOLD) {
                            problems.add(String.format("%s ~ %s = %.3f", left, right, similarity));
                        }
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "Имена разных записей неразличимы для lookupByName, исход решит порядок обхода:\n  "
                        + String.join("\n  ", problems));
    }

    private static final Set<String> KNOWN_OPTION_KEYS = Set.of(
            "endpoint", "versionPath", "downloadPath", "url", "loaders", "gameVersions"
    );

    @Test
    void everyOptionReadByCodeIsDelivered() throws Exception {
        java.lang.reflect.Field field = SourceCatalog.class.getDeclaredField("OPTION_FIELDS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> deliveredList = (List<String>) field.get(null);
        Set<String> delivered = new LinkedHashSet<>(deliveredList);

        List<String> problems = new ArrayList<>();
        for (String key : KNOWN_OPTION_KEYS) {
            if (!delivered.contains(key)) {
                problems.add("источники читают «" + key + "», но OPTION_FIELDS его не переносит — всегда null");
            }
        }

        assertTrue(problems.isEmpty(), "Опции теряются между каталогом и источниками:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void directSourceReceivesAllOptionsFromCatalog() {
        SourceCatalog catalog = new SourceCatalog(null);
        List<SourceCatalog.CatalogSource> sources = catalog.lookup("me.lucko.luckperms.bukkit.loader.BukkitLoaderPlugin", "LuckPerms");
        assertNotNull(sources);
        SourceCatalog.CatalogSource direct = sources.stream()
                .filter(s -> "direct".equalsIgnoreCase(s.sourceId()))
                .findFirst()
                .orElse(null);
        assertNotNull(direct, "LuckPerms must have direct source in catalog");
        assertTrue(direct.options().containsKey("endpoint"));
        assertTrue(direct.options().containsKey("downloadPath"));
        assertTrue(direct.options().containsKey("versionPath"));
    }

    private static Set<String> implementedSourceIds() throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        for (Class<?> clazz : SOURCE_CLASSES) {
            java.lang.reflect.Field idField = clazz.getDeclaredField("ID");
            idField.setAccessible(true);
            ids.add((String) idField.get(null));
        }
        return ids;
    }

    private static Set<String> optionKeysReadByCode() {
        return KNOWN_OPTION_KEYS;
    }
}
