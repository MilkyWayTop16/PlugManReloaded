package ru.milkyway.plugmanreloaded.update.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.AssetUtil;
import ru.milkyway.plugmanreloaded.update.HttpJson;
import ru.milkyway.plugmanreloaded.update.MatchConfidence;
import ru.milkyway.plugmanreloaded.update.MatchReason;
import ru.milkyway.plugmanreloaded.update.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.ReleaseChannel;
import ru.milkyway.plugmanreloaded.update.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateCache;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class JenkinsSource implements UpdateSource {

    public static final String ID = "jenkins";

    private static final String BUILD_PATH =
            "/lastSuccessfulBuild/api/json?tree=number,timestamp,artifacts%5BfileName,relativePath%5D";
    private static final double MIN_ASSET_SIMILARITY = 0.55;

    private final UpdateCache cache;
    private final Map<String, String> endpoints = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();

    public JenkinsSource(UpdateCache cache) {
        this.cache = cache;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supportsAutoInstall() {
        return false;
    }

    @Override
    public Map<String, ProjectMatch> identifyBatch(List<PluginIdentity> identities) {
        return Collections.emptyMap();
    }

    @Override
    public @Nullable ProjectMatch identifyFromCatalog(PluginIdentity identity, @Nullable String ref, Map<String, String> options) {
        if (ref == null || options == null) {
            return null;
        }
        String endpoint = options.get("endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            Log.debug("jenkinssource.endpoint-missing", "ref", ref);
            return null;
        }

        endpoints.put(ref, endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        names.put(ref, identity.pluginName());

        return new ProjectMatch(
                identity.pluginName(),
                ref,
                options.getOrDefault("url", endpoint),
                MatchConfidence.CONFIRMED,
                MatchReason.CATALOG,
                null
        );
    }

    @Override
    public List<RemoteVersion> listVersions(@Nullable ProjectMatch match) {
        List<RemoteVersion> versions = new ArrayList<>();
        if (match == null || match.projectRef() == null) {
            return versions;
        }

        String endpoint = endpoints.get(match.projectRef());
        if (endpoint == null) {
            return versions;
        }

        String cacheKey = ID + ":versions:" + match.projectRef();
        @SuppressWarnings("unchecked")
        List<RemoteVersion> cached = cache.get(cacheKey, List.class);
        if (cached != null) {
            return cached;
        }

        HttpJson.Response response = HttpJson.get(endpoint + BUILD_PATH);
        if (!response.ok() || !response.body().isJsonObject()) {
            return versions;
        }

        JsonObject build = response.body().getAsJsonObject();
        JsonArray artifacts = build.getAsJsonArray("artifacts");
        if (artifacts == null || artifacts.size() == 0) {
            return versions;
        }

        String pluginName = names.getOrDefault(match.projectRef(), match.projectRef());
        JsonObject best = pickArtifact(artifacts, pluginName);
        if (best == null) {
            return versions;
        }

        String number = build.has("number") && !build.get("number").isJsonNull()
                ? build.get("number").getAsString()
                : null;
        if (number == null) {
            return versions;
        }

        long timestamp = build.has("timestamp") && !build.get("timestamp").isJsonNull()
                ? build.get("timestamp").getAsLong()
                : 0L;

        versions.add(new RemoteVersion(
                ID,
                match.projectRef(),
                match.projectUrl(),
                "build-" + number,
                ReleaseChannel.ALPHA,
                Set.of(),
                Set.of(),
                endpoint + "/lastSuccessfulBuild/artifact/" + string(best, "relativePath"),
                string(best, "fileName"),
                null,
                null,
                0L,
                timestamp > 0 ? Instant.ofEpochMilli(timestamp) : Instant.EPOCH
        ));

        cache.put(cacheKey, versions);
        return versions;
    }

    private @Nullable JsonObject pickArtifact(JsonArray artifacts, String pluginName) {
        JsonObject best = null;
        double bestScore = -1.0;

        for (JsonElement element : artifacts) {
            if (!element.isJsonObject()) continue;
            JsonObject artifact = element.getAsJsonObject();

            String fileName = string(artifact, "fileName");
            if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (AssetUtil.isNonRuntimeArtifact(fileName)) {
                continue;
            }
            if (AssetUtil.isCompanion(pluginName, fileName)) {
                continue;
            }

            double score = AssetUtil.similarity(pluginName, fileName) + AssetUtil.platformBonus(fileName);
            if (score > bestScore) {
                bestScore = score;
                best = artifact;
            }
        }

        if (best != null && bestScore < MIN_ASSET_SIMILARITY) {
            Log.debug("jenkinssource.no-matching-artifact", "plugin", pluginName);
            return null;
        }
        return best;
    }

    private static @Nullable String string(@Nullable JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }
}

