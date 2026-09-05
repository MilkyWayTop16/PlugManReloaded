package ru.milkyway.plugmanreloaded.download;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.download.models.SearchResultEntry;
import ru.milkyway.plugmanreloaded.update.AssetUtil;
import ru.milkyway.plugmanreloaded.update.HttpJson;
import ru.milkyway.plugmanreloaded.update.NameUtil;
import ru.milkyway.plugmanreloaded.update.ServerProfile;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class PluginSearch {

    private final PlugManReloaded plugin;
    private final ServerProfile serverProfile;
    private final SourceCatalog catalog;
    private final Cache<String, List<SearchResultEntry>> cache;

    public PluginSearch(PlugManReloaded plugin, ServerProfile serverProfile, SourceCatalog catalog) {
        this(plugin, serverProfile, catalog, null);
    }

    PluginSearch(PlugManReloaded plugin, ServerProfile serverProfile, SourceCatalog catalog, @Nullable Ticker ticker) {
        this.plugin = plugin;
        this.serverProfile = serverProfile;
        this.catalog = catalog;
        CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(10, TimeUnit.MINUTES);
        if (ticker != null) {
            builder.ticker(ticker);
        }
        this.cache = builder.build();
    }

    void clearCache() {
        cache.invalidateAll();
    }

    long cacheSize() {
        cache.cleanUp();
        return cache.size();
    }

    void putCachedDirect(String key, List<SearchResultEntry> entries) {
        cache.put(key, entries);
    }

    @Nullable
    List<SearchResultEntry> getCachedDirect(String key) {
        return cache.getIfPresent(key);
    }

    public List<SearchResultEntry> search(@Nullable String rawQuery, String preferredSource, int limit) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Collections.emptyList();
        }

        long startTime = System.currentTimeMillis();
        String query = rawQuery.trim();
        String cacheKey = (preferredSource != null ? preferredSource.toLowerCase(Locale.ROOT) : "all") + ":" + query.toLowerCase(Locale.ROOT);

        List<SearchResultEntry> cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            Log.debug("pluginsearch.cache-hit", "query", query, "count", String.valueOf(cached.size()));
            return cached;
        }

        Log.debug("pluginsearch.querying-sources", "query", query, "source", preferredSource != null ? preferredSource : "all");

        List<CompletableFuture<List<SearchResultEntry>>> futures = new ArrayList<>();

        boolean searchAll = preferredSource == null || preferredSource.isBlank() || "all".equalsIgnoreCase(preferredSource);

        if (searchAll || "modrinth".equalsIgnoreCase(preferredSource)) {
            futures.add(CompletableFuture.supplyAsync(() -> searchModrinth(query)));
        }
        if (searchAll || "hangar".equalsIgnoreCase(preferredSource)) {
            futures.add(CompletableFuture.supplyAsync(() -> searchHangar(query)));
        }
        if (searchAll || "spigot".equalsIgnoreCase(preferredSource) || "spigotmc".equalsIgnoreCase(preferredSource)) {
            futures.add(CompletableFuture.supplyAsync(() -> searchSpiget(query)));
        }
        if (searchAll || "github".equalsIgnoreCase(preferredSource)) {
            futures.add(CompletableFuture.supplyAsync(() -> searchGithub(query)));
        }

        List<SearchResultEntry> combined = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            for (CompletableFuture<List<SearchResultEntry>> f : futures) {
                try {
                    List<SearchResultEntry> list = f.getNow(Collections.emptyList());
                    if (list != null) combined.addAll(list);
                } catch (Exception t) {
                    Log.debug("pluginsearch.source-no-response", t);
                }
            }
        } catch (Exception timeout) {
            Log.debug("pluginsearch.not-all-sources-responded", timeout);
            for (CompletableFuture<List<SearchResultEntry>> f : futures) {
                try {
                    List<SearchResultEntry> list = f.getNow(Collections.emptyList());
                    if (list != null) combined.addAll(list);
                } catch (Exception t) {
                    Log.debug("pluginsearch.source-no-response", t);
                }
            }
        }

        List<SearchResultEntry> scored = new ArrayList<>();
        for (SearchResultEntry entry : combined) {
            double score = computeRelevanceScore(query, entry);
            scored.add(entry.withScore(score));
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<SearchResultEntry> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (SearchResultEntry entry : scored) {
            String key = entry.sourceId() + ":" + entry.projectId().toLowerCase(Locale.ROOT);
            if (seenKeys.add(key)) {
                result.add(entry);
                if (result.size() >= limit) break;
            }
        }

        List<SearchResultEntry> finalResult = enrichVersions(result);
        long elapsed = System.currentTimeMillis() - startTime;
        Log.info("pluginsearch.search-finished", "query", query, "count", String.valueOf(finalResult.size()), "elapsed", String.valueOf(elapsed));

        cache.put(cacheKey, finalResult);
        return finalResult;
    }

    private List<SearchResultEntry> enrichVersions(@Nullable List<SearchResultEntry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        List<CompletableFuture<SearchResultEntry>> futures = new ArrayList<>();
        for (SearchResultEntry entry : entries) {
            if (entry.version() != null && !entry.version().isBlank()) {
                futures.add(CompletableFuture.completedFuture(entry));
            } else {
                futures.add(CompletableFuture.supplyAsync(() -> fetchLatestVersion(entry)));
            }
        }
        List<SearchResultEntry> out = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(2500, TimeUnit.MILLISECONDS);
            for (CompletableFuture<SearchResultEntry> f : futures) {
                out.add(f.getNow(null));
            }
        } catch (Exception t) {
            Log.debug("pluginsearch.version-load-timeout", t);
            for (int i = 0; i < entries.size(); i++) {
                try {
                    SearchResultEntry resolved = futures.get(i).getNow(entries.get(i));
                    out.add(resolved != null ? resolved : entries.get(i));
                } catch (Exception fallbackErr) {
                    Log.debug("pluginsearch.version-fetch-failed", fallbackErr, "project", entries.get(i).projectId());
                    out.add(entries.get(i));
                }
            }
        }
        return out;
    }

    private @Nullable SearchResultEntry fetchLatestVersion(@Nullable SearchResultEntry entry) {
        if (entry == null) return null;

        String source = entry.sourceId() != null ? entry.sourceId().toLowerCase(Locale.ROOT) : "";
        try {
            return switch (source) {
                case "modrinth" -> modrinthVersion(entry);
                case "hangar" -> hangarVersion(entry);
                case "spigot" -> spigotVersion(entry);
                case "github" -> githubRelease(entry);
                default -> entry;
            };
        } catch (Exception t) {
            Log.debug("pluginsearch.version-fetch-failed", t, "project", entry.projectId());
            return entry;
        }
    }

    private static SearchResultEntry modrinthVersion(SearchResultEntry entry) {
        String projectId = URLEncoder.encode(entry.projectId(), StandardCharsets.UTF_8);
        String serverLoaders = "%5B%22paper%22%2C%22purpur%22%2C%22folia%22%2C%22spigot%22%2C%22bukkit%22%5D";

        String version = firstModrinthVersion(
                "https://api.modrinth.com/v2/project/" + projectId + "/version?loaders=" + serverLoaders + "&limit=1");
        if (version == null) {
            version = firstModrinthVersion("https://api.modrinth.com/v2/project/" + projectId + "/version?limit=1");
        }
        return version != null ? entry.withVersion(version) : entry;
    }

    private static @Nullable String firstModrinthVersion(String url) {
        HttpJson.Response response = HttpJson.get(url);
        if (!response.ok() || !response.body().isJsonArray()) {
            return null;
        }
        JsonArray versions = response.body().getAsJsonArray();
        if (versions.size() == 0 || !versions.get(0).isJsonObject()) {
            return null;
        }
        String version = string(versions.get(0).getAsJsonObject(), "version_number", "");
        return version.isBlank() ? null : version;
    }

    private static SearchResultEntry hangarVersion(SearchResultEntry entry) {
        HttpJson.RawResponse response = HttpJson.getRaw(
                "https://hangar.papermc.io/api/v1/projects/" + entry.projectId() + "/latestrelease");
        if (!response.ok() || response.body() == null) {
            return entry;
        }
        String version = response.body().trim().replace("\"", "");
        return version.isBlank() ? entry : entry.withVersion(version);
    }

    private static SearchResultEntry spigotVersion(SearchResultEntry entry) {
        HttpJson.Response response = HttpJson.get(
                "https://api.spiget.org/v2/resources/" + entry.projectId() + "/versions/latest");
        if (!response.ok() || !response.body().isJsonObject()) {
            return entry;
        }
        String version = string(response.body().getAsJsonObject(), "name", "");
        return version.isBlank() ? entry : entry.withVersion(version);
    }

    private SearchResultEntry githubRelease(SearchResultEntry entry) {
        String token = plugin != null && plugin.getConfigManager() != null ? plugin.getConfigManager().getGithubToken() : null;
        String authorization = token != null && !token.isBlank() ? "Bearer " + token.trim() : null;

        JsonObject release = null;
        HttpJson.Response latest = HttpJson.get(
                "https://api.github.com/repos/" + entry.projectId() + "/releases/latest", authorization);
        if (latest.ok() && latest.body().isJsonObject()) {
            release = latest.body().getAsJsonObject();
        } else {
            HttpJson.Response listing = HttpJson.get(
                    "https://api.github.com/repos/" + entry.projectId() + "/releases?per_page=1", authorization);
            if (listing.ok() && listing.body().isJsonArray()) {
                JsonArray releases = listing.body().getAsJsonArray();
                if (releases.size() > 0 && releases.get(0).isJsonObject()) {
                    release = releases.get(0).getAsJsonObject();
                }
            }
        }

        if (release == null) {
            return entry.withRelease(entry.version(), entry.downloadUrl(), false);
        }

        String version = string(release, "tag_name", "");
        JarAsset jar = firstJarAsset(release);
        return entry.withRelease(
                !version.isBlank() ? version : entry.version(),
                jar.downloadUrl() != null ? jar.downloadUrl() : entry.downloadUrl(),
                jar.present());
    }

    private record JarAsset(boolean present, @Nullable String downloadUrl) {}

    private static JarAsset firstJarAsset(JsonObject release) {
        if (!release.has("assets") || !release.get("assets").isJsonArray()) {
            return new JarAsset(false, null);
        }
        for (JsonElement element : release.getAsJsonArray("assets")) {
            if (!element.isJsonObject()) continue;

            JsonObject asset = element.getAsJsonObject();
            String name = string(asset, "name", "");
            if (name.toLowerCase(Locale.ROOT).endsWith(".jar") && !AssetUtil.isNonRuntimeArtifact(name)) {
                return new JarAsset(true, string(asset, "browser_download_url", null));
            }
        }
        return new JarAsset(false, null);
    }

    private List<SearchResultEntry> searchModrinth(String rawQuery) {
        List<SearchResultEntry> results = new ArrayList<>();
        String query = rawQuery != null ? rawQuery.trim() : "";
        if (query.isBlank()) return results;

        if (query.contains("modrinth.com/")) {
            var matcher = Pattern.compile("modrinth\\.com/(?:plugin|mod|project|datapack|resourcepack|shader|modpack)/([^/#?]+)").matcher(query);
            if (matcher.find()) {
                query = matcher.group(1);
            }
        }

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String facets = URLEncoder.encode("[[\"project_type:plugin\",\"project_type:mod\"],[\"loaders:paper\",\"loaders:purpur\",\"loaders:folia\",\"loaders:spigot\",\"loaders:bukkit\"]]", StandardCharsets.UTF_8);
            String url = "https://api.modrinth.com/v2/search?query=" + encoded + "&facets=" + facets + "&limit=6&index=relevance";

            HttpJson.Response resp = HttpJson.get(url);
            if (!resp.ok() || !resp.body().isJsonObject()) return results;

            JsonObject obj = resp.body().getAsJsonObject();
            if (!obj.has("hits") || !obj.get("hits").isJsonArray()) return results;

            JsonArray hits = obj.getAsJsonArray("hits");
            for (JsonElement e : hits) {
                if (!e.isJsonObject()) continue;
                JsonObject hit = e.getAsJsonObject();

                String slug = string(hit, "slug", "");
                String title = string(hit, "title", slug);
                String author = string(hit, "author", "Unknown");
                String desc = string(hit, "description", "");
                long downloads = longVal(hit, "downloads", 0);
                int follows = intVal(hit, "follows", 0);

                List<String> gameVersions = stringList(hit, "game_versions");
                if (gameVersions.isEmpty()) {
                    gameVersions = stringList(hit, "versions");
                }
                List<String> loaders = stringList(hit, "loaders");
                if (loaders.isEmpty()) {
                    loaders = stringList(hit, "categories");
                }

                String pageUrl = "https://modrinth.com/plugin/" + slug;

                SearchResultEntry entry = new SearchResultEntry(
                        "modrinth", slug, title, author, "", desc, pageUrl, null,
                        downloads, follows, 0.0, gameVersions, loaders, Collections.emptyList(),
                        null, null, null, false, true
                );
                results.add(entry);
            }
        } catch (Exception t) {
            Log.debug("pluginsearch.modrinth-search-failed", t, "query", query);
        }
        return results;
    }

    private List<SearchResultEntry> searchHangar(String rawQuery) {
        List<SearchResultEntry> results = new ArrayList<>();
        String query = rawQuery != null ? rawQuery.trim() : "";
        if (query.isBlank()) return results;

        if (query.contains("hangar.papermc.io/")) {
            var matcher = Pattern.compile("hangar\\.papermc\\.io/(?:[^/]+/)?([^/#?]+)").matcher(query);
            if (matcher.find()) {
                query = matcher.group(1);
            }
        }

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://hangar.papermc.io/api/v1/projects?q=" + encoded + "&limit=5&sort=-stars";

            HttpJson.Response resp = HttpJson.get(url);
            if (!resp.ok() || !resp.body().isJsonObject()) return results;

            JsonObject obj = resp.body().getAsJsonObject();
            if (!obj.has("result") || !obj.get("result").isJsonArray()) return results;

            JsonArray array = obj.getAsJsonArray("result");
            for (JsonElement e : array) {
                if (!e.isJsonObject()) continue;
                JsonObject p = e.getAsJsonObject();

                String name = string(p, "name", "");
                String desc = string(p, "description", "");
                String owner = "Hangar";
                String slug = name;

                if (p.has("namespace") && p.get("namespace").isJsonObject()) {
                    JsonObject ns = p.getAsJsonObject("namespace");
                    owner = string(ns, "owner", owner);
                    slug = string(ns, "slug", slug);
                }

                long downloads = 0;
                int stars = 0;
                if (p.has("stats") && p.get("stats").isJsonObject()) {
                    JsonObject stats = p.getAsJsonObject("stats");
                    downloads = longVal(stats, "downloads", 0);
                    stars = intVal(stats, "stars", 0);
                }

                String pageUrl = "https://hangar.papermc.io/" + owner + "/" + slug;
                String projectRef = owner + "/" + slug;

                SearchResultEntry entry = new SearchResultEntry(
                        "hangar", projectRef, name, owner, "", desc, pageUrl, null,
                        downloads, stars, 0.0, Collections.emptyList(), List.of("paper"), Collections.emptyList(),
                        null, null, null, false, true
                );
                results.add(entry);
            }
        } catch (Exception t) {
            Log.debug("pluginsearch.hangar-search-failed", t, "query", query);
        }
        return results;
    }

    private static final Map<Integer, String> SPIGET_AUTHORS = new ConcurrentHashMap<>();

    private String resolveSpigetAuthor(@Nullable JsonObject r) {
        if (r == null || !r.has("author")) return "SpigotMC";
        try {
            if (r.get("author").isJsonObject()) {
                int authorId = intVal(r.getAsJsonObject("author"), "id", 0);
                if (authorId > 0) {
                    String cached = SPIGET_AUTHORS.get(authorId);
                    if (cached != null) {
                        return cached;
                    }
                    HttpJson.Response aResp = HttpJson.get("https://api.spiget.org/v2/authors/" + authorId);
                    if (aResp.ok() && aResp.body().isJsonObject()) {
                        String aName = string(aResp.body().getAsJsonObject(), "name", "");
                        if (!aName.isBlank()) {
                            SPIGET_AUTHORS.put(authorId, aName);
                            return aName;
                        }
                    }
                }
            } else if (r.get("author").isJsonPrimitive()) {
                String a = r.get("author").getAsString();
                if (!a.isBlank()) return a;
            }
        } catch (Exception ignored) {}
        return "SpigotMC";
    }

    private int resolveSpigetStars(@Nullable JsonObject r) {
        if (r == null || !r.has("rating") || !r.get("rating").isJsonObject()) return 0;
        try {
            JsonObject rating = r.getAsJsonObject("rating");
            int avg = (int) Math.round(doubleVal(rating, "average", 0.0));
            if (avg > 0) return avg;
            return intVal(rating, "count", 0);
        } catch (Exception ignored) {}
        return 0;
    }

    private List<SearchResultEntry> searchSpiget(String rawQuery) {
        List<SearchResultEntry> results = new ArrayList<>();
        String query = rawQuery != null ? rawQuery.trim() : "";
        if (query.isBlank()) return results;

        String idCandidate = null;
        if (query.matches("^\\d+$")) {
            idCandidate = query;
        } else if (query.contains("spigotmc.org/resources/")) {
            var matcher = Pattern.compile("(?:/|\\.)(\\d+)(?:/|$|\\?|#)").matcher(query);
            if (matcher.find()) {
                idCandidate = matcher.group(1);
            }
        } else if (query.toLowerCase(Locale.ROOT).startsWith("spigot:")) {
            String sub = query.substring(7).trim();
            if (sub.matches("^\\d+$")) {
                idCandidate = sub;
            }
        }

        if (idCandidate != null) {
            try {
                String directUrl = "https://api.spiget.org/v2/resources/" + idCandidate;
                HttpJson.Response directResp = HttpJson.get(directUrl);
                if (directResp.ok() && directResp.body().isJsonObject()) {
                    JsonObject r = directResp.body().getAsJsonObject();
                    int id = intVal(r, "id", 0);
                    if (id > 0) {
                        String rawName = string(r, "name", "Resource " + id);
                        String cleanName = NameUtil.cleanResourceTitle(rawName);
                        String name = !cleanName.isBlank() ? cleanName : rawName;
                        String tag = string(r, "tag", "");
                        long downloads = longVal(r, "downloads", 0);
                        int stars = resolveSpigetStars(r);
                        String author = resolveSpigetAuthor(r);
                        boolean premium = boolVal(r, "premium", false);
                        boolean external = boolVal(r, "external", false);

                        String pageUrl = "https://www.spigotmc.org/resources/" + id;
                        boolean direct = !premium && !external;

                        List<String> gameVersions = stringList(r, "testedVersions");

                        SearchResultEntry entry = new SearchResultEntry(
                                "spigot", String.valueOf(id), name, author, "", tag, pageUrl, null,
                                downloads, stars, 0.0, gameVersions, List.of("spigot", "paper"), Collections.emptyList(),
                                null, null, null, premium, direct
                        );
                        results.add(entry);
                        return results;
                    }
                }
            } catch (Exception t) {
                Log.debug("pluginsearch.spiget-fetch-failed", t, "id", idCandidate);
            }
        }

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.spiget.org/v2/search/resources/" + encoded + "?field=name&size=5&sort=-downloads";

            HttpJson.Response resp = HttpJson.get(url);
            if (!resp.ok() || !resp.body().isJsonArray()) return results;

            JsonArray array = resp.body().getAsJsonArray();
            for (JsonElement e : array) {
                if (!e.isJsonObject()) continue;
                JsonObject r = e.getAsJsonObject();

                int id = intVal(r, "id", 0);
                if (id <= 0) continue;

                String rawName = string(r, "name", "Resource " + id);
                String cleanName = NameUtil.cleanResourceTitle(rawName);
                String name = !cleanName.isBlank() ? cleanName : rawName;
                String tag = string(r, "tag", "");
                long downloads = longVal(r, "downloads", 0);
                int stars = resolveSpigetStars(r);
                String author = resolveSpigetAuthor(r);
                boolean premium = boolVal(r, "premium", false);
                boolean external = boolVal(r, "external", false);

                String pageUrl = "https://www.spigotmc.org/resources/" + id;
                boolean direct = !premium && !external;

                List<String> gameVersions = stringList(r, "testedVersions");

                SearchResultEntry entry = new SearchResultEntry(
                        "spigot", String.valueOf(id), name, author, "", tag, pageUrl, null,
                        downloads, stars, 0.0, gameVersions, List.of("spigot", "paper"), Collections.emptyList(),
                        null, null, null, premium, direct
                );
                results.add(entry);
            }
        } catch (Exception t) {
            Log.debug("pluginsearch.spiget-search-failed", t, "query", query);
        }
        return results;
    }

    private List<SearchResultEntry> searchGithub(String rawQuery) {
        List<SearchResultEntry> results = new ArrayList<>();
        String query = rawQuery != null ? rawQuery.trim() : "";
        if (query.isBlank()) return results;

        String repoCandidate = null;
        if (query.contains("github.com/")) {
            var matcher = Pattern.compile("github\\.com/([^/]+/[^/#?]+)").matcher(query);
            if (matcher.find()) {
                repoCandidate = matcher.group(1).replaceAll("\\.git$", "");
            }
        } else if (query.matches("^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$")) {
            repoCandidate = query;
        }

        String token = plugin != null && plugin.getConfigManager() != null ? plugin.getConfigManager().getGithubToken() : null;
        String authorization = (token != null && !token.isBlank()) ? "Bearer " + token.trim() : null;

        if (repoCandidate != null) {
            try {
                String directUrl = "https://api.github.com/repos/" + repoCandidate;
                HttpJson.Response directResp = HttpJson.get(directUrl, authorization);
                if (directResp.ok() && directResp.body().isJsonObject()) {
                    JsonObject repo = directResp.body().getAsJsonObject();
                    String fullName = string(repo, "full_name", repoCandidate);
                    String name = string(repo, "name", fullName);
                    String desc = string(repo, "description", "");
                    String htmlUrl = string(repo, "html_url", "https://github.com/" + fullName);
                    int stars = intVal(repo, "stargazers_count", 0);
                    List<String> topics = stringList(repo, "topics");

                    String owner = fullName.contains("/") ? fullName.split("/")[0] : "GitHub";

                    SearchResultEntry entry = new SearchResultEntry(
                            "github", fullName, name, owner, "", desc, htmlUrl, null,
                            0, stars, 0.0, Collections.emptyList(), topics, Collections.emptyList(),
                            null, null, null, false, true
                    );
                    results.add(entry);
                    return results;
                }
            } catch (Exception t) {
                Log.debug("pluginsearch.github-repo-fetch-failed", t, "repo", repoCandidate);
            }
        }

        try {
            String encoded = URLEncoder.encode(query + " in:name,description,topics", StandardCharsets.UTF_8);
            String url = "https://api.github.com/search/repositories?q=" + encoded + "&sort=stars&order=desc&per_page=6";

            HttpJson.Response resp = HttpJson.get(url, authorization);
            if (!resp.ok()) {
                if (resp.status() == 403 || resp.rateLimited()) {
                    Log.warn("pluginsearch.github-rate-limited");
                }
                return results;
            }

            JsonObject obj = resp.body().getAsJsonObject();
            if (!obj.has("items") || !obj.get("items").isJsonArray()) return results;

            JsonArray items = obj.getAsJsonArray("items");
            for (JsonElement e : items) {
                if (!e.isJsonObject()) continue;
                JsonObject repo = e.getAsJsonObject();

                String fullName = string(repo, "full_name", "");
                String name = string(repo, "name", fullName);
                String desc = string(repo, "description", "");
                String htmlUrl = string(repo, "html_url", "https://github.com/" + fullName);
                int stars = intVal(repo, "stargazers_count", 0);
                List<String> topics = stringList(repo, "topics");

                String owner = fullName.contains("/") ? fullName.split("/")[0] : "GitHub";

                SearchResultEntry entry = new SearchResultEntry(
                        "github", fullName, name, owner, "", desc, htmlUrl, null,
                        0, stars, 0.0, Collections.emptyList(), topics, Collections.emptyList(),
                        null, null, null, false, true
                );
                results.add(entry);
            }
        } catch (Exception t) {
            Log.debug("pluginsearch.github-search-failed", t, "query", query);
        }
        return results;
    }

    public double computeRelevanceScore(String query, SearchResultEntry entry) {
        String queryNorm = NameUtil.normalizeName(query);
        String titleNorm = NameUtil.normalizeName(entry.title());
        String idNorm = NameUtil.normalizeName(entry.projectId());
        boolean exactName = queryNorm.equalsIgnoreCase(titleNorm) || queryNorm.equalsIgnoreCase(idNorm);
        boolean pluginLike = looksLikePlugin(entry);

        double score = nameMatchScore(queryNorm, titleNorm, idNorm)
                + catalogScore(query, entry)
                + gameVersionScore(entry)
                + popularityScore(entry, exactName)
                + wrongKindPenalty(query, entry)
                + platformScore(query, entry, pluginLike)
                + githubNoisePenalty(entry, pluginLike, exactName);
        return Math.max(0.0, score);
    }

    private static double nameMatchScore(String queryNorm, String titleNorm, String idNorm) {
        if (queryNorm.equalsIgnoreCase(titleNorm) || queryNorm.equalsIgnoreCase(idNorm)) {
            return 50.0;
        }
        if (titleNorm.startsWith(queryNorm) || idNorm.startsWith(queryNorm)) {
            return 25.0;
        }
        return titleNorm.contains(queryNorm) || idNorm.contains(queryNorm) ? 15.0 : 5.0;
    }

    private double catalogScore(String query, SearchResultEntry entry) {
        if (catalog == null) {
            return 0.0;
        }
        for (SourceCatalog.CatalogSource source : catalog.lookupByName(query)) {
            if (source != null && source.sourceId().equalsIgnoreCase(entry.sourceId())
                    && (source.ref().equalsIgnoreCase(entry.projectId()) || source.ref().contains(entry.projectId()))) {
                return 30.0;
            }
        }
        return 0.0;
    }

    private double gameVersionScore(SearchResultEntry entry) {
        if (serverProfile == null) {
            return 0.0;
        }
        if (entry.gameVersions() == null || entry.gameVersions().isEmpty()) {
            return 5.0;
        }
        return entry.gameVersions().contains(serverProfile.minecraftVersion()) ? 15.0 : 0.0;
    }

    private static double popularityScore(SearchResultEntry entry, boolean exactName) {
        long downloads = entry.downloads();
        int stars = entry.stars();

        double score = 0.0;
        if (downloads >= 1_000_000) score += 15.0;
        else if (downloads >= 100_000) score += 12.0;
        else if (downloads >= 10_000) score += 8.0;
        else if (downloads >= 1_000) score += 5.0;

        if (stars >= 500) score += 15.0;
        else if (stars >= 100) score += 12.0;
        else if (stars >= 20) score += 8.0;
        else if (stars >= 5) score += 5.0;
        else if (downloads == 0 && stars == 0 && !exactName) score -= 5.0;

        return score;
    }

    private static double wrongKindPenalty(String query, SearchResultEntry entry) {
        String title = entry.title().toLowerCase(Locale.ROOT);
        String description = entry.description() != null ? entry.description().toLowerCase(Locale.ROOT) : "";
        String lowerQuery = query.toLowerCase(Locale.ROOT);

        double penalty = 0.0;
        if (!lowerQuery.contains("addon")
                && (title.contains("addon") || title.contains("expansion") || title.contains("extension"))) {
            penalty -= 25.0;
        }
        if (!lowerQuery.contains("fork") && (title.contains("fork") || description.startsWith("fork of"))) {
            penalty -= 15.0;
        }
        if (!lowerQuery.contains("config") && (title.contains("config") || title.contains("setup"))) {
            penalty -= 30.0;
        }
        return penalty;
    }

    private static double platformScore(String query, SearchResultEntry entry, boolean pluginLike) {
        if (pluginLike) {
            return 15.0;
        }
        return looksLikeMod(query, entry) ? -40.0 : 0.0;
    }

    private static double githubNoisePenalty(SearchResultEntry entry, boolean pluginLike, boolean exactName) {
        if (!"github".equalsIgnoreCase(entry.sourceId()) || exactName) {
            return 0.0;
        }
        String title = entry.title().toLowerCase(Locale.ROOT);
        String description = entry.description() != null ? entry.description().toLowerCase(Locale.ROOT) : "";
        boolean minecraftContext = pluginLike
                || title.contains("minecraft")
                || description.contains("minecraft")
                || hasLoader(entry, "minecraft");
        return minecraftContext ? 0.0 : -50.0;
    }

    private static boolean looksLikePlugin(SearchResultEntry entry) {
        if (hasLoader(entry, "plugin", "paper", "spigot", "bukkit", "purpur", "folia")) {
            return true;
        }
        String title = entry.title().toLowerCase(Locale.ROOT);
        String description = entry.description() != null ? entry.description().toLowerCase(Locale.ROOT) : "";
        return title.contains("plugin") || description.contains("plugin")
                || description.contains("spigot") || description.contains("paper") || description.contains("bukkit");
    }

    private static boolean looksLikeMod(String query, SearchResultEntry entry) {
        if (hasLoader(entry, "fabric", "forge", "neoforge", "quilt")) {
            return true;
        }
        if (query.toLowerCase(Locale.ROOT).contains("mod")) {
            return false;
        }
        String title = entry.title().toLowerCase(Locale.ROOT);
        String description = entry.description() != null ? entry.description().toLowerCase(Locale.ROOT) : "";
        return title.contains("fabric") || title.contains("forge") || title.contains("neoforge") || title.contains("quilt")
                || description.contains("fabric mod") || description.contains("forge mod") || description.contains("quilt mod");
    }

    private static boolean hasLoader(SearchResultEntry entry, String... markers) {
        if (entry.loaders() == null) {
            return false;
        }
        for (String loader : entry.loaders()) {
            String lower = loader.toLowerCase(Locale.ROOT);
            for (String marker : markers) {
                if (lower.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String string(JsonObject obj, String key, String def) {
        return (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) ? obj.get(key).getAsString().trim() : def;
    }

    private static long longVal(@Nullable JsonObject obj, String key, long def) {
        if (obj == null || !obj.has(key)) return def;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception t) {
            return def;
        }
    }

    private static double doubleVal(@Nullable JsonObject obj, String key, double def) {
        if (obj == null || !obj.has(key)) return def;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception t) {
            return def;
        }
    }

    private static int intVal(@Nullable JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key)) return def;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception t) {
            return def;
        }
    }

    private static boolean boolVal(@Nullable JsonObject obj, String key, boolean def) {
        if (obj == null || !obj.has(key)) return def;
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception t) {
            return def;
        }
    }

    private static List<String> stringList(@Nullable JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonElement e : obj.getAsJsonArray(key)) {
            if (e.isJsonPrimitive()) list.add(e.getAsString().trim());
        }
        return list;
    }
}

