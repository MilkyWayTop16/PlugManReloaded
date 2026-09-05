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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class GithubSource implements UpdateSource {

    public static final String ID = "github";

    private static final String API = "https://api.github.com";
    private static final int RELEASE_LIMIT = 15;
    private static final double MIN_ASSET_SIMILARITY = 0.55;
    private static final double MIN_OWNER_REPO_SIMILARITY = 0.85;

    private final UpdateCache cache;
    private final String token;

    private static final long RATE_LIMIT_COOLDOWN_MS = 15 * 60 * 1000L;

    private static final Pattern REPO_REF =
            Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    private volatile long rateLimitWarnedUntil = 0L;
    private volatile long rateLimitedUntil = 0L;

    public GithubSource(UpdateCache cache, String token) {
        this.cache = cache;
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public @Nullable String projectTitle(@Nullable String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        int slash = ref.lastIndexOf('/');
        return slash >= 0 && slash + 1 < ref.length() ? ref.substring(slash + 1) : ref;
    }

    @Override
    public boolean supportsAutoInstall() {
        return true;
    }

    public boolean isRateLimited() {
        return System.currentTimeMillis() < rateLimitedUntil;
    }

    @Override
    public @Nullable ProjectMatch identifyByName(@Nullable PluginIdentity identity) {
        if (identity == null || isRateLimited()) {
            return null;
        }

        String pluginName = identity.pluginName();
        if (pluginName == null || pluginName.isBlank()) {
            return null;
        }

        String cacheKey = ID + ":name:" + pluginName.toLowerCase(Locale.ROOT);
        ProjectMatch cached = cache.get(cacheKey, ProjectMatch.class);
        if (cached != null) {
            return cached;
        }
        if (Boolean.TRUE.equals(cache.get(cacheKey + ":miss", Boolean.class))) {
            return null;
        }

        if (identity.authors() != null && !identity.authors().isEmpty()) {
            for (String author : identity.authors()) {
                if (author == null || author.isBlank()) continue;
                ProjectMatch match = identifyFromOwner(identity, author.trim());
                if (match != null) {
                    cache.put(cacheKey, match);
                    return match;
                }
            }
        }

        cache.put(cacheKey + ":miss", Boolean.TRUE);
        return null;
    }

    @Override
    public Map<String, ProjectMatch> identifyBatch(List<PluginIdentity> identities) {
        return Collections.emptyMap();
    }

    @Override
    public @Nullable ProjectMatch identifyFromCatalog(PluginIdentity identity, @Nullable String ref, Map<String, String> options) {
        if (ref == null || !ref.contains("/")) {
            return null;
        }
        return new ProjectMatch(
                identity.pluginName(),
                ref,
                "https://github.com/" + ref,
                MatchConfidence.CONFIRMED,
                MatchReason.CATALOG,
                null
        );
    }

    public @Nullable ProjectMatch identifyFromOwner(@Nullable PluginIdentity identity, String owner) {
        if (identity == null || identity.isPremium() || owner == null || owner.isBlank() || isRateLimited()) {
            return null;
        }

        List<String> repoNames = fetchOwnerRepoNames(owner);
        if (repoNames == null) {
            return null;
        }

        String normalizedPlugin = NameUtil.normalizeName(identity.pluginName());
        String bestRepo = null;
        double bestScore = -1.0;

        for (String repoName : repoNames) {
            if (NameUtil.isCompanion(identity.pluginName(), repoName)) continue;

            double score = NameUtil.similarity(normalizedPlugin, NameUtil.normalizeName(repoName));
            if (score > bestScore) {
                bestScore = score;
                bestRepo = repoName;
            }
        }

        if (bestRepo == null || bestScore < MIN_OWNER_REPO_SIMILARITY) {
            return null;
        }
        return ownerMatch(identity, owner, bestRepo);
    }

    private @Nullable List<String> fetchOwnerRepoNames(String owner) {
        String cacheKey = ID + ":owner:" + owner.toLowerCase(Locale.ROOT);
        @SuppressWarnings("unchecked")
        List<String> cached = cache.get(cacheKey, List.class);
        if (cached != null) {
            return cached;
        }
        if (Boolean.TRUE.equals(cache.get(cacheKey + ":miss", Boolean.class))) {
            return null;
        }

        String url = API + "/users/" + HttpJson.encodePath(owner) + "/repos?per_page=100&sort=updated";
        String authorization = token.isEmpty() ? null : "Bearer " + token;
        HttpJson.Response response = HttpJson.get(url, authorization);

        if (!response.ok() || !response.body().isJsonArray()) {
            if (response.rateLimited()) {
                rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS;
            } else if (!response.transportFailure()) {
                cache.put(cacheKey + ":miss", Boolean.TRUE);
            }
            return null;
        }

        List<String> names = new ArrayList<>();
        for (JsonElement element : response.body().getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            String name = string(element.getAsJsonObject(), "name");
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }

        if (names.isEmpty()) {
            cache.put(cacheKey + ":miss", Boolean.TRUE);
            return null;
        }

        cache.put(cacheKey, names);
        return names;
    }

    private ProjectMatch ownerMatch(PluginIdentity identity, String owner, String repo) {
        String ref = owner + "/" + repo;
        return new ProjectMatch(
                identity.pluginName(),
                ref,
                "https://github.com/" + ref,
                MatchConfidence.LIKELY,
                MatchReason.NAME_FUZZY,
                null
        );
    }

    @Override
    public List<RemoteVersion> listVersions(@Nullable ProjectMatch match) {
        List<RemoteVersion> versions = new ArrayList<>();
        if (match == null || match.projectRef() == null) {
            return versions;
        }

        if (!REPO_REF.matcher(match.projectRef()).matches()) {
            Log.debugPlain("githubsource.invalid-repo-ref", "ref", match.projectRef());
            return versions;
        }

        String cacheKey = ID + ":versions:" + match.projectRef() + ":" + (match.pluginName() == null ? "" : match.pluginName());
        @SuppressWarnings("unchecked")
        List<RemoteVersion> cached = cache.get(cacheKey, List.class);
        if (cached != null) {
            return cached;
        }

        if (!isRateLimited()) {
            String url = API + "/repos/" + match.projectRef() + "/releases?per_page=10";
            String authorization = token.isEmpty() ? null : "Bearer " + token;
            HttpJson.Response response = HttpJson.get(url, authorization);

            if (response.ok() && response.body().isJsonArray()) {
                for (JsonElement element : response.body().getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    RemoteVersion version = parseRelease(element.getAsJsonObject(), match);
                    if (version != null) {
                        versions.add(version);
                    }
                }
                if (!versions.isEmpty()) {
                    cache.put(cacheKey, versions);
                    return versions;
                }
            } else if (response.rateLimited()) {
                rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS;
                warnAboutRateLimit();
            }
        }

        List<RemoteVersion> atomFallback = parseAtomFeed(match);
        if (!atomFallback.isEmpty()) {
            return atomFallback;
        }

        return versions;
    }

    private void warnAboutRateLimit() {
        long now = System.currentTimeMillis();
        if (now < rateLimitWarnedUntil) {
            return;
        }
        rateLimitWarnedUntil = now + RATE_LIMIT_COOLDOWN_MS;
        if (token.isEmpty()) {
            Log.warn("githubsource.rate-limited-no-token");
        } else {
            Log.warn("githubsource.rate-limited-with-token");
        }
    }

    private List<RemoteVersion> parseAtomFeed(ProjectMatch match) {
        List<RemoteVersion> versions = new ArrayList<>();
        try {
            String url = "https://github.com/" + match.projectRef() + "/releases.atom";
            HttpJson.RawResponse response = HttpJson.getRaw(url);
            if (!response.ok()) return versions;

            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc;
            try (InputStream is = new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8))) {
                doc = factory.newDocumentBuilder().parse(is);
            }

            org.w3c.dom.NodeList entries = doc.getElementsByTagName("entry");
            String pluginName = match.pluginName() != null ? match.pluginName() : pluginNameFromRef(match.projectRef());

            for (int i = 0; i < entries.getLength(); i++) {
                org.w3c.dom.Element entry = (org.w3c.dom.Element) entries.item(i);
                org.w3c.dom.NodeList linkNodes = entry.getElementsByTagName("link");
                if (linkNodes.getLength() == 0) continue;
                String href = ((org.w3c.dom.Element) linkNodes.item(0)).getAttribute("href");
                int tagIndex = href.lastIndexOf("/tag/");
                if (tagIndex < 0) continue;
                String tag = href.substring(tagIndex + 5);

                String rawUpdated = "";
                org.w3c.dom.NodeList updatedNodes = entry.getElementsByTagName("updated");
                if (updatedNodes.getLength() > 0) {
                    rawUpdated = updatedNodes.item(0).getTextContent();
                }

                versions.add(new RemoteVersion(
                        ID,
                        match.projectRef(),
                        match.projectUrl(),
                        tag,
                        ReleaseChannel.parse(tag),
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        0L,
                        parseInstant(rawUpdated)
                ));
            }
        } catch (Throwable t) {
            Log.debug("githubsource.atom-feed-failed", t, "ref", match.projectRef());
        }
        return versions;
    }

    private @Nullable RemoteVersion parseRelease(JsonObject release, ProjectMatch match) {
        if (bool(release, "draft")) {
            return null;
        }

        JsonArray assets = release.getAsJsonArray("assets");
        if (assets == null || assets.size() == 0) {
            return null;
        }

        String pluginName = match.pluginName() != null ? match.pluginName() : pluginNameFromRef(match.projectRef());
        JsonObject best = pickAsset(assets, pluginName);
        if (best == null) {
            return null;
        }

        String tag = string(release, "tag_name");
        ReleaseChannel channel = bool(release, "prerelease")
                ? ReleaseChannel.BETA
                : ReleaseChannel.parse(tag);
        if (!bool(release, "prerelease") && channel == ReleaseChannel.UNKNOWN) {
            channel = ReleaseChannel.RELEASE;
        }

        return new RemoteVersion(
                ID,
                match.projectRef(),
                match.projectUrl(),
                tag,
                channel,
                Set.of(),
                Set.of(),
                string(best, "browser_download_url"),
                string(best, "name"),
                null,
                digest(best),
                best.has("size") ? best.get("size").getAsLong() : 0L,
                parseInstant(string(release, "published_at"))
        );
    }

    private @Nullable JsonObject pickAsset(JsonArray assets, String pluginName) {
        JsonObject best = null;
        double bestScore = -1.0;

        for (JsonElement element : assets) {
            if (!element.isJsonObject()) continue;
            JsonObject asset = element.getAsJsonObject();

            String name = string(asset, "name");
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (AssetUtil.isNonRuntimeArtifact(name)) {
                continue;
            }
            if (AssetUtil.isCompanion(pluginName, name)) {
                continue;
            }

            double score = AssetUtil.similarity(pluginName, name) + AssetUtil.platformBonus(name);
            if (score > bestScore) {
                bestScore = score;
                best = asset;
            }
        }

        if (best != null && bestScore < MIN_ASSET_SIMILARITY) {
            int candidateJars = 0;
            JsonObject singleCandidate = null;
            for (JsonElement element : assets) {
                if (!element.isJsonObject()) continue;
                JsonObject asset = element.getAsJsonObject();
                String name = string(asset, "name");
                if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        && !AssetUtil.isNonRuntimeArtifact(name)
                        && !AssetUtil.isCompanion(pluginName, name)) {
                    candidateJars++;
                    singleCandidate = asset;
                }
            }
            if (candidateJars == 1) {
                return singleCandidate;
            }
            Log.debug("githubsource.no-matching-asset", "plugin", pluginName, "bestScore", String.valueOf(bestScore));
            return null;
        }
        return best;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String pluginNameFromRef(String ref) {
        int slash = ref.indexOf('/');
        return slash >= 0 ? ref.substring(slash + 1) : ref;
    }

    private static @Nullable String digest(JsonObject asset) {
        String raw = string(asset, "digest");
        if (raw == null) return null;
        int colon = raw.indexOf(':');
        return colon >= 0 ? raw.substring(colon + 1) : raw;
    }

    private static Instant parseInstant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(raw);
        } catch (Throwable t) {
            return Instant.EPOCH;
        }
    }

    private static boolean bool(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() && object.get(field).getAsBoolean();
    }

    private static @Nullable String string(@Nullable JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }
}

