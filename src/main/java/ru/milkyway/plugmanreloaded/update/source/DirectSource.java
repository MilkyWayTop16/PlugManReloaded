package ru.milkyway.plugmanreloaded.update.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DirectSource implements UpdateSource {

    public static final String ID = "direct";

    private final UpdateCache cache;
    private final Map<String, Map<String, String>> descriptors = new ConcurrentHashMap<>();

    public DirectSource(UpdateCache cache) {
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
        if (ref == null || options == null) {
            return null;
        }
        String endpoint = options.get("endpoint");
        String downloadPath = options.get("downloadPath");
        if (endpoint == null || endpoint.isBlank() || downloadPath == null || downloadPath.isBlank()) {
            Log.debug("directsource.endpoint-missing", "ref", ref);
            return null;
        }

        descriptors.put(ref, new HashMap<>(options));
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

        Map<String, String> descriptor = descriptors.get(match.projectRef());
        if (descriptor == null) {
            return versions;
        }

        String cacheKey = ID + ":versions:" + match.projectRef();
        @SuppressWarnings("unchecked")
        List<RemoteVersion> cached = cache.get(cacheKey, List.class);
        if (cached != null) {
            return cached;
        }

        HttpJson.Response response = HttpJson.get(descriptor.get("endpoint"));
        if (!response.ok() || !response.body().isJsonObject()) {
            return versions;
        }

        JsonObject root = response.body().getAsJsonObject();
        String version = readPath(root, descriptor.getOrDefault("versionPath", "version"));
        String downloadUrl = readPath(root, descriptor.get("downloadPath"));

        if (version == null || version.isBlank() || downloadUrl == null || downloadUrl.isBlank()) {
            Log.debug("directsource.no-version-or-url", "endpoint", String.valueOf(descriptor.get("endpoint")));
            return versions;
        }

        Set<String> loaders = Set.of("bukkit", "spigot", "paper", "purpur", "folia");
        if (descriptor.containsKey("loaders")) {
            loaders = Set.of(descriptor.get("loaders").split(","));
        }

        Set<String> gameVersions = Set.of("all");
        if (descriptor.containsKey("gameVersions")) {
            gameVersions = Set.of(descriptor.get("gameVersions").split(","));
        }

        versions.add(new RemoteVersion(
                ID,
                match.projectRef(),
                match.projectUrl(),
                version,
                ReleaseChannel.RELEASE,
                gameVersions,
                loaders,
                downloadUrl,
                fileNameFromUrl(downloadUrl),
                null,
                null,
                0L,
                Instant.now()
        ));

        cache.put(cacheKey, versions);
        return versions;
    }

    private static @Nullable String readPath(@Nullable JsonObject root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        JsonElement current = root;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }

        if (current == null || !current.isJsonPrimitive()) {
            return null;
        }
        return current.getAsString();
    }

    private static String fileNameFromUrl(String url) {
        int query = url.indexOf('?');
        String clean = query >= 0 ? url.substring(0, query) : url;
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        return name.isBlank() ? "downloaded-plugin.jar" : name;
    }
}

