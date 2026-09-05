package ru.milkyway.plugmanreloaded.update.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.AssetUtil;
import ru.milkyway.plugmanreloaded.update.HttpJson;
import ru.milkyway.plugmanreloaded.update.MatchConfidence;
import ru.milkyway.plugmanreloaded.update.MatchReason;
import ru.milkyway.plugmanreloaded.update.NameUtil;
import ru.milkyway.plugmanreloaded.update.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.ReleaseChannel;
import ru.milkyway.plugmanreloaded.update.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateCache;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ModrinthSource implements UpdateSource {

    public static final String ID = "modrinth";

    private static final String API = "https://api.modrinth.com/v2";
    private static final int HASH_BATCH_SIZE = 100;

    private final UpdateCache cache;

    public ModrinthSource(UpdateCache cache) {
        this.cache = cache;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supportsAutoInstall() {
        return true;
    }

    @Override
    public Map<String, ProjectMatch> identifyBatch(@Nullable List<PluginIdentity> identities) {
        Map<String, ProjectMatch> result = new HashMap<>();
        if (identities == null || identities.isEmpty()) {
            return result;
        }

        Map<String, String> hashToPlugin = new HashMap<>();
        List<String> hashes = new ArrayList<>();
        for (PluginIdentity identity : identities) {
            if (identity.hashesAvailable()) {
                String hash = identity.sha1().toLowerCase(Locale.ROOT);
                hashToPlugin.put(hash, identity.pluginName());
                hashes.add(hash);
            }
        }
        if (hashes.isEmpty()) {
            return result;
        }

        for (int start = 0; start < hashes.size(); start += HASH_BATCH_SIZE) {
            List<String> batch = hashes.subList(start, Math.min(start + HASH_BATCH_SIZE, hashes.size()));
            resolveHashBatch(batch, hashToPlugin, result);
        }
        return result;
    }

    private void resolveHashBatch(List<String> batch, Map<String, String> hashToPlugin, Map<String, ProjectMatch> out) {
        StringBuilder payload = new StringBuilder("{\"hashes\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) payload.append(',');
            payload.append('"').append(batch.get(i)).append('"');
        }
        payload.append("],\"algorithm\":\"sha1\"}");

        HttpJson.Response response = HttpJson.postJson(API + "/version_files", payload.toString());
        if (!response.ok() || !response.body().isJsonObject()) {
            if (response.status() == -1) {
                Log.debug("modrinthsource.hash-batch-failed");
            }
            return;
        }

        JsonObject body = response.body().getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            String pluginName = hashToPlugin.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (pluginName == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject version = entry.getValue().getAsJsonObject();
            String projectId = string(version, "project_id");
            if (projectId == null) {
                continue;
            }
            out.put(pluginName, new ProjectMatch(
                    pluginName,
                    projectId,
                    "https://modrinth.com/plugin/" + projectId,
                    MatchConfidence.CONFIRMED,
                    MatchReason.HASH_MATCH,
                    string(version, "version_number")
            ));
        }
    }

    @Override
    public @Nullable ProjectMatch identifyFromCatalog(PluginIdentity identity, @Nullable String ref, Map<String, String> options) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return new ProjectMatch(
                identity.pluginName(),
                ref,
                "https://modrinth.com/plugin/" + ref,
                MatchConfidence.CONFIRMED,
                MatchReason.CATALOG,
                null
        );
    }

    @Override
    public @Nullable ProjectMatch identifyByName(@Nullable PluginIdentity identity) {
        if (identity == null || identity.isPremium()) {
            return null;
        }
        String pluginName = identity.pluginName();
        if (pluginName == null || pluginName.isBlank()) {
            return null;
        }

        String cacheKey = ID + ":search:" + pluginName.toLowerCase(Locale.ROOT);
        ProjectMatch cached = cache.get(cacheKey, ProjectMatch.class);
        if (cached != null) {
            return cached;
        }
        if (Boolean.TRUE.equals(cache.get(cacheKey + ":miss", Boolean.class))) {
            return null;
        }

        List<String> queries = NameUtil.getSearchAliases(pluginName);
        boolean transportFailure = false;
        for (String q : queries) {
            String url = API + "/search?query=" + encode(q)
                    + "&facets=" + encode("[[\"project_type:plugin\"]]")
                    + "&limit=10&index=downloads";

            HttpJson.Response response = HttpJson.get(url);
            if (response.transportFailure()) {
                transportFailure = true;
                continue;
            }
            if (!response.ok() || !response.body().isJsonObject()) {
                continue;
            }

            JsonArray hits = response.body().getAsJsonObject().getAsJsonArray("hits");
            if (hits == null || hits.size() == 0) {
                continue;
            }

            ProjectMatch best = pickSearchHit(identity, hits);
            if (best != null) {
                cache.put(cacheKey, best);
                return best;
            }
        }

        if (!transportFailure) {
            cache.put(cacheKey + ":miss", Boolean.TRUE);
        }
        return null;
    }

    private @Nullable ProjectMatch pickSearchHit(PluginIdentity identity, JsonArray hits) {
        String normalizedPlugin = NameUtil.normalizeName(identity.pluginName());
        List<String> aliases = NameUtil.getSearchAliases(identity.pluginName());

        String bestSlug = null;
        int bestTier = Integer.MAX_VALUE;
        int bestTierCount = 0;

        for (JsonElement element : hits) {
            if (!element.isJsonObject()) continue;
            JsonObject hit = element.getAsJsonObject();

            String slug = string(hit, "slug");
            String title = string(hit, "title");
            if (slug == null || title == null) continue;

            if (NameUtil.isCompanion(identity.pluginName(), title)
                    || NameUtil.isCompanion(identity.pluginName(), slug)) {
                continue;
            }

            boolean aliasMatch = false;
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(slug) || NameUtil.normalizeName(alias).equalsIgnoreCase(NameUtil.normalizeName(slug))) {
                    aliasMatch = true;
                    break;
                }
            }

            if (!aliasMatch) {
                double titleScore = NameUtil.resourceNameSimilarity(identity.pluginName(), title);
                double slugScore = NameUtil.similarity(normalizedPlugin, NameUtil.normalizeName(slug));
                if (Math.max(titleScore, slugScore) < 0.85) {
                    continue;
                }

                boolean authorMatch = NameUtil.authorsMatch(identity.authors(), string(hit, "author"));
                boolean brandMatch = NameUtil.hasBrandCorroboration(identity, slug, title, string(hit, "description"));
                String remoteAuthor = string(hit, "author");
                if (identity.authors() != null && !identity.authors().isEmpty() && remoteAuthor != null && !remoteAuthor.isBlank()) {
                    if (!NameUtil.isAuthorCompatible(identity.authors(), remoteAuthor) && !brandMatch) {
                        Log.debug("modrinthsource.candidate-author-mismatch", "slug", slug, "remoteAuthor", remoteAuthor);
                        continue;
                    }
                }
                boolean distinctive = NameUtil.isDistinctive(normalizedPlugin)
                        && !NameUtil.isGeneric(identity.pluginName());

                if (!authorMatch && !brandMatch && !distinctive) {
                    Log.debug("modrinthsource.candidate-no-corroboration", "slug", slug);
                    continue;
                }
            }

            int tier = aliasMatch ? 0 : 1;
            if (tier < bestTier) {
                bestTier = tier;
                bestSlug = slug;
                bestTierCount = 1;
            } else if (tier == bestTier) {
                bestTierCount++;
            }
        }

        if (bestSlug == null) {
            return null;
        }

        MatchConfidence confidence = MatchConfidence.LIKELY;
        if (bestTierCount > 1) {
            confidence = MatchConfidence.WEAK;
            Log.debug("modrinthsource.ambiguous-match", "plugin", identity.pluginName(), "count", String.valueOf(bestTierCount));
        }

        return new ProjectMatch(
                identity.pluginName(),
                bestSlug,
                "https://modrinth.com/plugin/" + bestSlug,
                confidence,
                MatchReason.NAME_FUZZY,
                null
        );
    }

    @Override
    public @Nullable String projectTitle(@Nullable String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String key = ID + ":title:" + ref;
        String cached = cache.get(key, String.class);
        if (cached != null) {
            return cached;
        }
        HttpJson.Response response = HttpJson.get(API + "/project/" + HttpJson.encodePath(ref));
        if (!response.ok() || !response.body().isJsonObject()) {
            return null;
        }
        String title = string(response.body().getAsJsonObject(), "title");
        if (title == null || title.isBlank()) {
            return null;
        }
        cache.put(key, title);
        return title;
    }

    @Override
    public List<RemoteVersion> listVersions(@Nullable ProjectMatch match) {
        List<RemoteVersion> versions = new ArrayList<>();
        if (match == null || match.projectRef() == null) {
            return versions;
        }

        String cacheKey = ID + ":versions:" + match.projectRef() + ":" + (match.pluginName() == null ? "" : match.pluginName());
        @SuppressWarnings("unchecked")
        List<RemoteVersion> cached = cache.get(cacheKey, List.class);
        if (cached != null) {
            return cached;
        }

        String url = API + "/project/" + HttpJson.encodePath(match.projectRef()) + "/version?loaders="
                + encode("[\"paper\",\"spigot\",\"bukkit\",\"folia\",\"purpur\"]");
        HttpJson.Response response = HttpJson.get(url);
        if (!response.ok() || !response.body().isJsonArray()) {
            if (response.rateLimited()) {
                Log.warn("modrinthsource.rate-limited");
            }
            return versions;
        }

        JsonArray array = response.body().getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            RemoteVersion version = parseVersion(element.getAsJsonObject(), match);
            if (version != null) {
                versions.add(version);
            }
        }

        cache.put(cacheKey, versions);
        return versions;
    }

    private @Nullable RemoteVersion parseVersion(JsonObject json, ProjectMatch match) {
        try {
            JsonArray files = json.getAsJsonArray("files");
            if (files == null || files.size() == 0) {
                return null;
            }

            String targetName = match.pluginName() != null ? match.pluginName() : match.projectRef();
            JsonObject primary = pickFile(files, targetName);
            if (primary == null) {
                return null;
            }

            JsonObject hashes = primary.getAsJsonObject("hashes");

            return new RemoteVersion(
                    ID,
                    match.projectRef(),
                    match.projectUrl(),
                    string(json, "version_number"),
                    ReleaseChannel.parse(string(json, "version_type")),
                    stringSet(json.getAsJsonArray("game_versions")),
                    stringSet(json.getAsJsonArray("loaders")),
                    string(primary, "url"),
                    string(primary, "filename"),
                    hashes != null ? string(hashes, "sha1") : null,
                    null,
                    primary.has("size") ? primary.get("size").getAsLong() : 0L,
                    parseInstant(string(json, "date_published"))
            );
        } catch (Exception t) {
            Log.debug("modrinthsource.version-parse-failed", t, "project", match.projectRef());
            return null;
        }
    }

    private @Nullable JsonObject pickFile(@Nullable JsonArray files, String targetName) {
        if (files == null || files.size() == 0) return null;

        JsonObject best = null;
        double bestScore = -1.0;

        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            String filename = string(file, "filename");
            if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (AssetUtil.isNonRuntimeArtifact(filename)) {
                continue;
            }
            if (AssetUtil.isCompanion(targetName, filename)) {
                continue;
            }

            double score = AssetUtil.similarity(targetName, filename) + AssetUtil.platformBonus(filename);
            boolean isPrimary = file.has("primary") && file.get("primary").getAsBoolean();
            if (isPrimary) {
                score += 0.05;
            }
            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }

        if (best != null && bestScore >= 0.70) {
            return best;
        }

        return null;
    }

    private static Instant parseInstant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(raw);
        } catch (Exception t) {
            return Instant.EPOCH;
        }
    }

    private static Set<String> stringSet(@Nullable JsonArray array) {
        Set<String> result = new LinkedHashSet<>();
        if (array == null) return result;
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static @Nullable String string(@Nullable JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

