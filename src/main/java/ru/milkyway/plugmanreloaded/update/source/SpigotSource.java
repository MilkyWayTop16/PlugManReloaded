package ru.milkyway.plugmanreloaded.update.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SpigotSource implements UpdateSource {

    public static final String ID = "spigot";
    public static final String ID_PREMIUM = "spigot-premium";

    private static final String API = "https://api.spiget.org/v2";
    private static final int VERSION_LIMIT = 5;

    private final UpdateCache cache;

    public SpigotSource(UpdateCache cache) {
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
    public Map<String, ProjectMatch> identifyBatch(List<PluginIdentity> identities) {
        return Collections.emptyMap();
    }

    @Override
    public @Nullable ProjectMatch identifyFromCatalog(PluginIdentity identity, @Nullable String ref, Map<String, String> options) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return new ProjectMatch(
                identity.pluginName(),
                ref,
                "https://www.spigotmc.org/resources/" + ref,
                MatchConfidence.CONFIRMED,
                MatchReason.CATALOG,
                null
        );
    }

    @Override
    public @Nullable ProjectMatch identifyByName(PluginIdentity identity) {
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

        boolean transportFailure = false;
        for (String alias : NameUtil.getSearchAliases(pluginName)) {
            String url = API + "/search/resources/" + HttpJson.encodePath(alias) + "?field=name&size=10";
            HttpJson.Response response = HttpJson.get(url);
            if (response.transportFailure()) {
                transportFailure = true;
                continue;
            }
            if (!response.ok() || !response.body().isJsonArray()) {
                continue;
            }

            ProjectMatch best = pickSearchHit(identity, response.body().getAsJsonArray());
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

        String bestId = null;
        boolean bestPremium = false;
        int qualified = 0;

        for (JsonElement element : hits) {
            if (!element.isJsonObject()) continue;
            JsonObject hit = element.getAsJsonObject();

            String title = string(hit, "name");
            if (title == null || !hit.has("id") || hit.get("id").isJsonNull()) {
                continue;
            }
            if (NameUtil.isCompanion(identity.pluginName(), title)) {
                continue;
            }

            double score = NameUtil.resourceNameSimilarity(identity.pluginName(), title);
            if (score < 0.90) {
                continue;
            }

            boolean brandMatch = NameUtil.hasBrandCorroboration(identity, title, string(hit, "tag"));
            boolean distinctive = NameUtil.isDistinctive(normalizedPlugin)
                    && !NameUtil.isGeneric(identity.pluginName());

            if (!brandMatch && !distinctive) {
                Log.debug("spigotsource.candidate-no-brand", "title", title);
                continue;
            }

            String resourceId = hit.get("id").getAsString();
            boolean premium = hit.has("premium") && !hit.get("premium").isJsonNull() && hit.get("premium").getAsBoolean();
            if (premium) {
                cache.put(ID + ":premium:" + resourceId, Boolean.TRUE);
            }

            if (identity.isPremium() && !premium) {
                continue;
            }
            if (!identity.isPremium() && premium) {
                continue;
            }

            qualified++;
            if (bestId == null) {
                bestId = resourceId;
                bestPremium = premium;
            }
        }

        if (bestId == null) {
            return null;
        }

        MatchConfidence confidence = MatchConfidence.LIKELY;
        if (qualified > 1) {
            confidence = MatchConfidence.WEAK;
            Log.debug("spigotsource.ambiguous-match", "plugin", identity.pluginName(), "count", String.valueOf(qualified));
        }
        if (bestPremium) {
            cache.put(ID + ":premium:" + bestId, Boolean.TRUE);
        }

        return new ProjectMatch(
                identity.pluginName(),
                bestId,
                "https://www.spigotmc.org/resources/" + bestId,
                confidence,
                MatchReason.NAME_FUZZY,
                null
        );
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public List<RemoteVersion> listVersions(@Nullable ProjectMatch match) {
        List<RemoteVersion> versions = new ArrayList<>();
        if (match == null || match.projectRef() == null) {
            return versions;
        }

        String cacheKey = ID + ":versions:" + match.projectRef();
        @SuppressWarnings("unchecked")
        List<RemoteVersion> cached = cache.get(cacheKey, List.class);
        if (cached != null) {
            return cached;
        }

        String url = API + "/resources/" + match.projectRef() + "/versions?size=" + VERSION_LIMIT + "&sort=-releaseDate";
        HttpJson.Response response = HttpJson.get(url);
        if (!response.ok() || !response.body().isJsonArray()) {
            if (response.rateLimited()) {
                Log.warn("spigotsource.rate-limited");
            }
            return versions;
        }

        JsonArray array = response.body().getAsJsonArray();
        boolean premium = isPremiumResource(match.projectRef());
        boolean canDownload = !premium && !isExternalResource(match.projectRef());
        Set<String> loaders = Set.of("bukkit", "spigot", "paper", "purpur", "folia");
        boolean newest = true;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            RemoteVersion version = parseVersion(element.getAsJsonObject(), match, premium, canDownload && newest, loaders);
            if (version != null) {
                versions.add(version);
                newest = false;
            }
        }

        cache.put(cacheKey, versions);
        return versions;
    }

    private static String buildFileName(ProjectMatch match, String versionName) {
        String base = match.pluginName() != null && !match.pluginName().isBlank()
                ? match.pluginName()
                : "resource-" + match.projectRef();
        String cleanBase = base.replaceAll("[^A-Za-z0-9._-]", "");
        String cleanVersion = versionName == null ? "" : versionName.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleanBase.isBlank()) {
            cleanBase = "plugin";
        }
        if (cleanVersion.isBlank()) {
            return cleanBase + ".jar";
        }
        return cleanBase + "-" + cleanVersion + ".jar";
    }

    private void loadResourceFlags(@Nullable String resourceId) {
        if (resourceId == null || resourceId.isBlank()) return;
        String missKey = ID + ":flags-miss:" + resourceId;
        if (Boolean.TRUE.equals(cache.get(missKey, Boolean.class))) {
            return;
        }
        HttpJson.Response info = HttpJson.get(API + "/resources/" + resourceId + "?fields=id,name,premium,external,file");
        if (!info.ok() || !info.body().isJsonObject()) {
            if (!info.transportFailure()) {
                cache.put(missKey, Boolean.TRUE);
            }
            Log.debug("spigotsource.resource-attributes-failed", "resourceId", resourceId);
            return;
        }
        JsonObject json = info.body().getAsJsonObject();
        boolean premium = flag(json, "premium", false);
        boolean external = flag(json, "external", false);
        if (json.has("file") && json.get("file").isJsonObject()) {
            JsonObject f = json.getAsJsonObject("file");
            if (f.has("externalUrl") && !f.get("externalUrl").isJsonNull()) {
                String extUrl = f.get("externalUrl").getAsString();
                if (!extUrl.isBlank()) external = true;
            }
        }
        cache.put(ID + ":premium:" + resourceId, premium);
        cache.put(ID + ":external:" + resourceId, external);
        String name = string(json, "name");
        if (name != null && !name.isBlank()) {
            cache.put(ID + ":title:" + resourceId, name);
        }
    }

    @Override
    public @Nullable String projectTitle(@Nullable String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String key = ID + ":title:" + ref;
        String cached = cache.get(key, String.class);
        if (cached == null) {
            loadResourceFlags(ref);
            cached = cache.get(key, String.class);
        }
        return cached;
    }

    private static boolean flag(JsonObject json, String field, boolean fallback) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            return fallback;
        }
        return json.get(field).getAsBoolean();
    }

    private boolean isExternalResource(@Nullable String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return false;
        }
        String key = ID + ":external:" + resourceId;
        Boolean cached = cache.get(key, Boolean.class);
        if (cached == null) {
            loadResourceFlags(resourceId);
            cached = cache.get(key, Boolean.class);
        }
        return cached != null && cached;
    }

    private boolean isPremiumResource(@Nullable String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return false;
        }
        String premiumKey = ID + ":premium:" + resourceId;
        Boolean cached = cache.get(premiumKey, Boolean.class);
        if (cached == null) {
            loadResourceFlags(resourceId);
            cached = cache.get(premiumKey, Boolean.class);
        }
        return cached != null && cached;
    }

    private @Nullable RemoteVersion parseVersion(JsonObject json, ProjectMatch match, boolean premium, boolean withDownload,
                                       Set<String> loaders) {
        try {
            String name = string(json, "name");
            if (name == null || name.isBlank()) {
                return null;
            }

            long released = json.has("releaseDate") && !json.get("releaseDate").isJsonNull()
                    ? json.get("releaseDate").getAsLong()
                    : 0L;

            String downloadUrl = withDownload ? API + "/resources/" + match.projectRef() + "/download" : null;
            String fileName = withDownload ? buildFileName(match, name) : null;

            return new RemoteVersion(
                    premium ? ID_PREMIUM : ID,
                    match.projectRef(),
                    match.projectUrl(),
                    name,
                    ReleaseChannel.parse(name),
                    Set.of(),
                    loaders,
                    downloadUrl,
                    fileName,
                    null,
                    null,
                    0L,
                    released > 0 ? Instant.ofEpochSecond(released) : Instant.EPOCH
            );
        } catch (Exception t) {
            Log.debug("spigotsource.version-parse-failed", t, "ref", match.projectRef());
            return null;
        }
    }

    private static @Nullable String string(@Nullable JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }
}

