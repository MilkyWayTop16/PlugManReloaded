package ru.milkyway.plugmanreloaded.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("live")
class PluginSourcesLiveTest {

    private static final double MIN_TITLE_SIMILARITY = 0.85;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private record Probe(String plugin, List<String> acceptedNames, String sourceId, String ref, String url,
                          String titlePath) {}

    private record Answer(int status, JsonObject body) {}

    private static Answer fetch(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "PlugManReloaded-CatalogAudit")
                    .header("Accept", "application/json")
                    .GET();

            String token = System.getenv("GITHUB_TOKEN");
            if (token != null && !token.isBlank() && url.startsWith("https://api.github.com/")) {
                builder.header("Authorization", "Bearer " + token.trim());
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject body = null;
            if (response.statusCode() / 100 == 2 && !response.body().isBlank()) {
                JsonElement parsed = JsonParser.parseString(response.body());
                if (parsed.isJsonObject()) {
                    body = parsed.getAsJsonObject();
                }
            }
            return new Answer(response.statusCode(), body);
        } catch (Exception e) {
            return new Answer(-1, null);
        }
    }

    private static List<Probe> probes() throws Exception {
        List<Probe> probes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (InputStream in = PluginSourcesLiveTest.class.getClassLoader()
                .getResourceAsStream("plugin-sources.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();

            for (JsonElement element : root.getAsJsonArray("plugins")) {
                JsonObject entry = element.getAsJsonObject();
                String plugin = entry.get("name").getAsString();

                List<String> acceptedNames = new ArrayList<>();
                acceptedNames.add(plugin);
                JsonElement aliases = entry.get("aliases");
                if (aliases != null && aliases.isJsonArray()) {
                    for (JsonElement alias : aliases.getAsJsonArray()) {
                        acceptedNames.add(alias.getAsString());
                    }
                }

                for (JsonElement rawElement : entry.getAsJsonArray("sources")) {
                    JsonObject raw = rawElement.getAsJsonObject();
                    String id = raw.get("id").getAsString();
                    String ref = raw.get("ref").getAsString();
                    if (!seen.add(id + "|" + ref)) continue;

                    switch (id) {
                        case "spigot" -> probes.add(new Probe(plugin, acceptedNames, id, ref,
                                "https://api.spiget.org/v2/resources/" + ref, "name"));
                        case "modrinth" -> probes.add(new Probe(plugin, acceptedNames, id, ref,
                                "https://api.modrinth.com/v2/project/" + ref, "title"));
                        case "hangar" -> probes.add(new Probe(plugin, acceptedNames, id, ref,
                                "https://hangar.papermc.io/api/v1/projects/" + ref, "name"));
                        case "github" -> probes.add(new Probe(plugin, acceptedNames, id, ref,
                                "https://api.github.com/repos/" + ref, null));
                        case "jenkins" -> probes.add(new Probe(plugin, acceptedNames, id, ref,
                                raw.get("endpoint").getAsString() + "/api/json", null));
                        case "direct" -> probes.add(new Probe(plugin, acceptedNames, id, ref,
                                raw.get("endpoint").getAsString(), null));
                        default -> { }
                    }
                }
            }
        }
        return probes;
    }

    @Test
    void catalogRefsResolveToTheRightResource() throws Exception {
        List<Probe> probes = probes();
        List<String> problems = new ArrayList<>();
        int reachable = 0;

        for (Probe probe : probes) {
            Answer answer = fetch(probe.url());
            String at = probe.plugin() + "/" + probe.sourceId() + " (" + probe.ref() + ")";

            if (answer.status() < 0 || answer.status() >= 500 || answer.status() == 429
                    || (answer.status() == 403 && "github".equals(probe.sourceId()))) {
                continue;
            }
            reachable++;

            if (answer.status() == 404 || answer.body() == null) {
                problems.add(at + ": ссылка не разрешается, ответ " + answer.status());
                continue;
            }
            if (probe.titlePath() == null) {
                continue;
            }

            JsonElement title = answer.body().get(probe.titlePath());
            if (title == null || title.isJsonNull()) {
                problems.add(at + ": в ответе нет поля " + probe.titlePath());
                continue;
            }

            double similarity = 0.0;
            for (String candidate : probe.acceptedNames()) {
                similarity = Math.max(similarity, PluginMatcher.resourceNameSimilarity(candidate, title.getAsString()));
            }
            if (similarity < MIN_TITLE_SIMILARITY) {
                problems.add(String.format("%s: ref ведёт на «%s», а запись про «%s» (схожесть %.2f)",
                        at, title.getAsString(), probe.plugin(), similarity));
            }
        }

        Assumptions.assumeTrue(reachable > probes.size() / 2,
                "Площадки недоступны из этой сети, живая проверка каталога пропущена");
        assertTrue(problems.isEmpty(), "Каталог ссылается не туда:\n  " + String.join("\n  ", problems));
    }
}
