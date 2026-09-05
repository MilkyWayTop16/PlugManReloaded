package ru.milkyway.plugmanreloaded.update.source;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.HttpJson;
import ru.milkyway.plugmanreloaded.update.MatchConfidence;
import ru.milkyway.plugmanreloaded.update.MatchReason;
import ru.milkyway.plugmanreloaded.update.NameUtil;
import ru.milkyway.plugmanreloaded.update.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.ReleaseChannel;
import ru.milkyway.plugmanreloaded.update.UpdateCache;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RusPigotSource implements UpdateSource {

    private static final String ID = "ruspigot";
    public static final String ID_PREMIUM = "ruspigot-premium";
    private static final String SITE = "https://spigotmc.ru";
    private static final int CANDIDATE_LIMIT = 6;
    private static final double MIN_TITLE_SIMILARITY = 0.90;

    private static final Pattern RESOURCE_LINK = Pattern.compile(
            "<a[^>]+href=\"(/resources/[a-zA-Z0-9%._-]+\\.\\d+/?)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL
    );
    private static final Pattern GIT_FIELD = Pattern.compile(
            "data-field=\"git\"(.{0,600}?)</dl>",
            Pattern.DOTALL
    );
    private static final Pattern ANY_HREF = Pattern.compile("href=\"(https?://[^\"]+)\"");
    private static final Pattern GITHUB_REPO = Pattern.compile(
            "https?://(?:www\\.)?github\\.com/([A-Za-z0-9._-]+/[A-Za-z0-9._-]+?)(?:/|\\.git)?(?:[?#].*)?$"
    );
    private static final Pattern TITLE_VERSION = Pattern.compile(
            "<h1[^>]*class=\"[^\"]*p-title-value[^\"]*\"[^>]*>.*?<span class=\"u-muted\">\\s*[vV]?([0-9][0-9A-Za-z._+-]*)\\s*</span>",
            Pattern.DOTALL
    );
    private static final Pattern VERSION_TEXT = Pattern.compile("Версия[^0-9]{0,30}([0-9][0-9A-Za-z._+-]{0,30})");

    private static final Object REQUEST_LOCK = new Object();
    private static long lastRequestTime = 0L;

    private final UpdateCache cache;
    private final GithubSource github;

    public RusPigotSource(UpdateCache cache, GithubSource github) {
        this.cache = cache;
        this.github = github;
    }

    private static HttpJson.RawResponse fetch(String url) {
        long waitTime = 0;
        synchronized (REQUEST_LOCK) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTime;
            if (elapsed < 120L) {
                waitTime = 120L - elapsed;
                lastRequestTime = now + waitTime;
            } else {
                lastRequestTime = now;
            }
        }
        if (waitTime > 0) {
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.debug("ruspigotsource.rate-limit-interrupted", interrupted);
            }
        }
        return HttpJson.getRaw(url);
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

        HttpJson.RawResponse search = fetch(SITE + "/search/search?keywords=" + encode(pluginName));
        if (search.transportFailure()) {
            return null;
        }
        if (!search.ok()) {
            cache.put(cacheKey + ":miss", Boolean.TRUE);
            return null;
        }

        Map<String, Candidate> candidates = extractSearchHits(search.body());
        boolean transportFailure = false;
        for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
            String path = entry.getKey();
            Candidate candidateInfo = entry.getValue();
            String title = candidateInfo.title();

            if (NameUtil.isCompanion(pluginName, title)) {
                continue;
            }
            if (titleSimilarity(pluginName, title) < MIN_TITLE_SIMILARITY) {
                continue;
            }

            String pageUrl = SITE + path;
            ResolvedResource resource = resolveResource(pageUrl);
            if (resource == null) {
                continue;
            }
            if (resource.transportFailure()) {
                transportFailure = true;
                continue;
            }

            if (!resource.paid() && resource.repoRef() != null) {
                ProjectMatch match = delegatedMatch(pluginName, resource.repoRef(), MatchReason.NAME_FUZZY);
                cache.put(cacheKey, match);
                return match;
            }

            boolean brandMatch = NameUtil.hasBrandCorroboration(identity, title, null);
            boolean distinctive = NameUtil.isDistinctive(NameUtil.normalizeName(pluginName))
                    && !NameUtil.isGeneric(pluginName);
            if (resource.paid() || brandMatch || distinctive) {
                ProjectMatch match = new ProjectMatch(
                        pluginName,
                        pageUrl,
                        pageUrl,
                        MatchConfidence.LIKELY,
                        MatchReason.NAME_FUZZY,
                        null
                );
                cache.put(cacheKey, match);
                return match;
            }
            Log.debug("ruspigotsource.candidate-rejected", "title", title);
        }

        if (!transportFailure) {
            cache.put(cacheKey + ":miss", Boolean.TRUE);
        }
        return null;
    }

    @Override
    public @Nullable ProjectMatch identifyFromCatalog(PluginIdentity identity, @Nullable String ref, Map<String, String> options) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String pageUrl = ref.startsWith("http") ? ref : SITE + "/resources/" + ref + "/";

        ResolvedResource resource = resolveResource(pageUrl);
        if (resource == null || resource.transportFailure()) {
            return null;
        }
        if (!resource.paid() && resource.repoRef() != null) {
            return delegatedMatch(identity.pluginName(), resource.repoRef(), MatchReason.CATALOG);
        }
        return new ProjectMatch(
                identity.pluginName(),
                pageUrl,
                pageUrl,
                MatchConfidence.LIKELY,
                MatchReason.CATALOG,
                null
        );
    }

    @Override
    public List<RemoteVersion> listVersions(@Nullable ProjectMatch match) {
        if (match == null || match.projectRef() == null) {
            return new ArrayList<>();
        }

        if (match.projectRef().startsWith("gh:")) {
            String repoRef = match.projectRef().substring(3);
            ProjectMatch githubMatch = new ProjectMatch(
                    match.pluginName(),
                    repoRef,
                    "https://github.com/" + repoRef,
                    match.confidence(),
                    match.reason(),
                    match.knownVersionNumber()
            );
            return github.listVersions(githubMatch);
        }

        List<RemoteVersion> versions = new ArrayList<>();
        String cacheKey = ID + ":versions:" + match.projectRef();
        @SuppressWarnings("unchecked")
        List<RemoteVersion> cached = cache.get(cacheKey, List.class);
        if (cached != null) {
            return cached;
        }

        HttpJson.RawResponse page = fetch(match.projectRef());
        if (page.transportFailure() || !page.ok()) {
            return versions;
        }

        boolean paid = isPaidResource(page.body());
        String versionNumber = extractVersionNumber(page.body());
        if (versionNumber != null && !versionNumber.isBlank()) {
            versions.add(new RemoteVersion(
                    paid ? ID_PREMIUM : ID,
                    match.projectRef(),
                    match.projectUrl(),
                    versionNumber,
                    ReleaseChannel.parse(versionNumber),
                    Set.of(),
                    Set.of(),
                    null,
                    null,
                    null,
                    null,
                    0L,
                    Instant.now()
            ));
        }

        cache.put(cacheKey, versions);
        return versions;
    }

    private static boolean isPaidResource(@Nullable String html) {
        if (html == null) return false;
        int titleIdx = html.indexOf("class=\"p-title");
        if (titleIdx >= 0) {
            int endIdx = html.indexOf("</h1>", titleIdx);
            if (endIdx < 0) {
                endIdx = html.indexOf("</div>", titleIdx);
            }
            if (endIdx > titleIdx) {
                String titleBlock = html.substring(titleIdx, endIdx);
                return titleBlock.contains("Платно") || titleBlock.contains("label--red");
            }
        }
        return false;
    }

    private static final Pattern DATE_LIKE = Pattern.compile(
            "^\\d{4}[-._]\\d{1,2}[-._]\\d{1,2}$|^\\d{1,2}[-._]\\d{1,2}[-._]\\d{4}$");

    private static boolean isDateLike(@Nullable String value) {
        if (value == null || value.isBlank()) return true;
        if (DATE_LIKE.matcher(value.trim()).matches()) {
            Log.debug("ruspigotsource.date-like-value", "value", value);
            return true;
        }
        return false;
    }

    private static @Nullable String extractVersionNumber(@Nullable String html) {
        if (html == null) return null;
        Matcher titleMatcher = TITLE_VERSION.matcher(html);
        if (titleMatcher.find()) {
            String ver = titleMatcher.group(1);
            if (!isDateLike(ver)) {
                return ver;
            }
        }
        Matcher matcher = VERSION_TEXT.matcher(html);
        if (matcher.find()) {
            String ver = matcher.group(1);
            if (!isDateLike(ver)) {
                return ver;
            }
        }
        return null;
    }

    private ProjectMatch delegatedMatch(String pluginName, String repoRef, MatchReason reason) {
        return new ProjectMatch(
                pluginName,
                "gh:" + repoRef,
                "https://github.com/" + repoRef,
                MatchConfidence.LIKELY,
                reason,
                null
        );
    }

    private record ResolvedResource(String repoRef, boolean paid, boolean transportFailure) {}

    private ResolvedResource resolveResource(String pageUrl) {
        HttpJson.RawResponse page = fetch(pageUrl);
        if (page.transportFailure()) {
            return new ResolvedResource(null, false, true);
        }
        if (!page.ok()) {
            return new ResolvedResource(null, false, false);
        }

        boolean paid = isPaidResource(page.body());

        Matcher gitField = GIT_FIELD.matcher(page.body());
        if (gitField.find()) {
            Matcher href = ANY_HREF.matcher(gitField.group(1));
            if (href.find()) {
                String link = href.group(1);
                Matcher repo = GITHUB_REPO.matcher(link);
                if (repo.matches()) {
                    String repoRef = repo.group(1);
                    if (!repoRef.isBlank()) {
                        return new ResolvedResource(repoRef, paid, false);
                    }
                }
                Log.debug("ruspigotsource.non-github-link", "link", link);
            }
        }
        return new ResolvedResource(null, paid, false);
    }

    private record Candidate(String title, boolean paid) {}

    private Map<String, Candidate> extractSearchHits(String html) {
        Map<String, Candidate> result = new LinkedHashMap<>();
        Matcher matcher = RESOURCE_LINK.matcher(html);
        while (matcher.find() && result.size() < CANDIDATE_LIMIT) {
            String path = matcher.group(1);
            String rawTitle = matcher.group(2);
            if (path.contains("latest") || path.contains("author")) {
                continue;
            }
            String title = plainText(rawTitle);
            if (title.isBlank()) {
                continue;
            }
            boolean paid = title.startsWith("Платно");
            result.putIfAbsent(path, new Candidate(title, paid));
        }
        return result;
    }

    private static String plainText(String html) {
        return html
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double titleSimilarity(String pluginName, String title) {
        String cleanTitle = cleanResourceTitle(title);
        double score = NameUtil.resourceNameSimilarity(pluginName, cleanTitle);
        String normPlugin = NameUtil.normalizeName(pluginName);
        for (String word : cleanTitle.split("[\\s|:«»()\\[\\]_-]+")) {
            String normWord = NameUtil.normalizeName(word);
            if (normWord.equals(normPlugin)) {
                return 1.0;
            }
            double wordScore = NameUtil.similarity(normPlugin, normWord);
            if (wordScore > score) {
                score = wordScore;
            }
        }
        return score;
    }

    private static String cleanResourceTitle(@Nullable String title) {
        if (title == null) return "";
        String s = title;
        for (int i = 0; i < 3; i++) {
            s = s.replaceAll("(?i)^(?:платно|бесплатно|плагин|перевод|конфигурация|модуль|самопис|addon)\\s*", "");
            s = s.replaceAll("^[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}\\p{P}\\s?]+", "");
        }
        return s.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

