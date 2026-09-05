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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HangarSource implements UpdateSource {

    public static final String ID = "hangar";

    private static final String API = "https://hangar.papermc.io/api/v1";
    private static final String PLATFORM = "PAPER";
    private static final int VERSION_LIMIT = 25;

    private final UpdateCache cache;

    public HangarSource(UpdateCache cache) {
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

        for (PluginIdentity identity : identities) {
            if (identity.sha256() == null || identity.sha256().isBlank()) {
                continue;
            }
            ProjectMatch match = confirmByHash(identity);
            if (match != null) {
                result.put(identity.pluginName(), match);
            }
        }
        return result;
    }

    @Override
    public @Nullable ProjectMatch identifyFromCatalog(PluginIdentity identity, @Nullable String ref, Map<String, String> options) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return new ProjectMatch(
                identity.pluginName(),
                ref,
                "https://hangar.papermc.io/" + ref,
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
        JsonObject project = resolveProject(identity);
        if (project == null) {
            return null;
        }

        String slug = slugOf(project);
        if (slug == null) {
            return null;
        }

        String title = string(project, "name");
        if (title != null && NameUtil.isCompanion(identity.pluginName(), title)) {
            return null;
        }

        if (!corroborated(identity, project, slug, title)) {
            Log.debug("hangarsource.candidate-rejected", "slug", slug);
            return null;
        }

        return new ProjectMatch(
                identity.pluginName(),
                slug,
                "https://hangar.papermc.io/" + slug,
                MatchConfidence.LIKELY,
                MatchReason.NAME_FUZZY,
                null
        );
    }

    private boolean corroborated(PluginIdentity identity, JsonObject project, String slug, String title) {
        JsonObject namespace = project.getAsJsonObject("namespace");
        String owner = namespace == null ? null : string(namespace, "owner");

        if (NameUtil.authorsMatch(identity.authors(), owner)) {
            return true;
        }
        if (NameUtil.hasBrandCorroboration(identity, slug, owner, title, string(project, "description"))) {
            return true;
        }
        if (identity.authors() != null && !identity.authors().isEmpty() && owner != null && !owner.isBlank()) {
            if (!NameUtil.isAuthorCompatible(identity.authors(), owner)) {
                return false;
            }
        }
        return NameUtil.isDistinctive(NameUtil.normalizeName(identity.pluginName()))
                && !NameUtil.isGeneric(identity.pluginName());
    }

    private @Nullable ProjectMatch confirmByHash(PluginIdentity identity) {
        JsonObject project = resolveProject(identity);
        if (project == null) {
            return null;
        }

        String slug = slugOf(project);
        if (slug == null) {
            return null;
        }

        ProjectMatch probe = new ProjectMatch(
                identity.pluginName(),
                slug,
                "https://hangar.papermc.io/" + slug,
                MatchConfidence.LIKELY,
                MatchReason.NAME_FUZZY,
                null
        );

        for (RemoteVersion version : listVersions(probe)) {
            if (version.expectedSha256() != null
                    && version.expectedSha256().equalsIgnoreCase(identity.sha256())) {
                return new ProjectMatch(
                        identity.pluginName(),
                        slug,
                        probe.projectUrl(),
                        MatchConfidence.CONFIRMED,
                        MatchReason.HASH_MATCH,
                        version.versionNumber()
                );
            }
        }
        return null;
    }

    private @Nullable JsonObject resolveProject(PluginIdentity identity) {
        String pluginName = identity.pluginName();
        if (pluginName == null || pluginName.isBlank()) {
            return null;
        }

        String cacheKey = ID + ":project:" + pluginName.toLowerCase(Locale.ROOT);
        JsonObject cached = cache.get(cacheKey, JsonObject.class);
        if (cached != null) {
            return cached;
        }
        if (Boolean.TRUE.equals(cache.get(cacheKey + ":miss", Boolean.class))) {
            return null;
        }

        JsonObject project = null;
        boolean transportFailure = false;
        for (String alias : NameUtil.getSearchAliases(pluginName)) {
            String url = API + "/projects/" + HttpJson.encodePath(alias);
            HttpJson.Response response = HttpJson.get(url);
            if (response.transportFailure()) {
                transportFailure = true;
                break;
            }
            if (response.ok() && response.body().isJsonObject()) {
                project = response.body().getAsJsonObject();
                break;
            }
        }

        if (project != null) {
            cache.put(cacheKey, project);
        } else if (!transportFailure) {
            cache.put(cacheKey + ":miss", Boolean.TRUE);
        }
        return project;
    }

    private static @Nullable String slugOf(JsonObject project) {
        JsonObject namespace = project.getAsJsonObject("namespace");
        if (namespace == null) {
            return null;
        }
        String slug = string(namespace, "slug");
        String owner = string(namespace, "owner");
        return slug == null || owner == null ? null : owner + "/" + slug;
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

        String slug = match.projectRef().contains("/")
                ? match.projectRef().substring(match.projectRef().indexOf('/') + 1)
                : match.projectRef();

        String url = API + "/projects/" + HttpJson.encodePath(slug) + "/versions?limit=" + VERSION_LIMIT;
        HttpJson.Response response = HttpJson.get(url);
        if (!response.ok() || !response.body().isJsonObject()) {
            if (response.rateLimited()) {
                Log.warn("hangarsource.rate-limited");
            }
            return versions;
        }

        JsonArray result = response.body().getAsJsonObject().getAsJsonArray("result");
        if (result == null) {
            return versions;
        }

        for (JsonElement element : result) {
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
            JsonObject platforms = getJsonObject(json, "platformDependencies");
            if (platforms == null || !platforms.has(PLATFORM)) {
                return null;
            }
            JsonElement platformDeps = platforms.get(PLATFORM);
            if (platformDeps == null || !platformDeps.isJsonArray()) {
                return null;
            }

            JsonObject downloads = getJsonObject(json, "downloads");
            JsonObject paperDownload = downloads != null ? getJsonObject(downloads, PLATFORM) : null;

            String downloadUrl = null;
            String fileName = null;
            String sha256 = null;
            long size = 0L;

            if (paperDownload != null) {
                downloadUrl = string(paperDownload, "downloadUrl");
                JsonObject fileInfo = getJsonObject(paperDownload, "fileInfo");
                if (fileInfo != null) {
                    fileName = string(fileInfo, "name");
                    sha256 = string(fileInfo, "sha256Hash");
                    if (fileInfo.has("sizeBytes") && !fileInfo.get("sizeBytes").isJsonNull()) {
                        size = fileInfo.get("sizeBytes").getAsLong();
                    }
                }
            }

            JsonObject channel = getJsonObject(json, "channel");

            return new RemoteVersion(
                    ID,
                    match.projectRef(),
                    match.projectUrl(),
                    string(json, "name"),
                    ReleaseChannel.parse(channel != null ? string(channel, "name") : null),
                    stringSet(platformDeps.getAsJsonArray()),
                    Set.of("paper"),
                    downloadUrl,
                    fileName,
                    null,
                    sha256,
                    size,
                    parseInstant(string(json, "createdAt"))
            );
        } catch (Exception t) {
            Log.debug("hangarsource.version-parse-failed", t, "project", match.projectRef());
            return null;
        }
    }

    private static JsonObject getJsonObject(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
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
        Set<String> values = new LinkedHashSet<>();
        if (array == null) return values;
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
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

