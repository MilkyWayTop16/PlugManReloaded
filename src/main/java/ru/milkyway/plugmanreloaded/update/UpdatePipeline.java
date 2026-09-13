package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.update.UpdateModels.MatchConfidence;
import ru.milkyway.plugmanreloaded.update.UpdateModels.MatchReason;
import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.UpdateModels.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.input.SourceUrlParser;
import ru.milkyway.plugmanreloaded.update.source.GithubSource;
import ru.milkyway.plugmanreloaded.update.source.HangarSource;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class UpdatePipeline {

    private final PlugManReloaded plugin;
    private final List<UpdateSource> sources;
    private final HangarSource hangarSource;
    private final JarScanner jarScanner;
    private final SourceCatalog catalog;
    private static final double JAR_REF_MIN_SIMILARITY = 0.90;

    public UpdatePipeline(PlugManReloaded plugin, List<UpdateSource> sources, HangarSource hangarSource, JarScanner jarScanner, SourceCatalog catalog) {
        this.plugin = plugin;
        this.sources = sources;
        this.hangarSource = hangarSource;
        this.jarScanner = jarScanner;
        this.catalog = catalog;
    }

    public UpdateCandidate resolvePipeline(PluginIdentity identity, VersionResolver resolver) {
        UpdateCandidate candidate = resolveSingleFromCatalog(identity, resolver);
        if (candidate != null && isConfirmed(candidate)) {
            return candidate;
        }

        UpdateCandidate website = resolveSingleFromWebsite(identity, resolver);
        if (website != null && isConfirmed(website)) {
            return website;
        }
        if (website != null && (candidate == null || UpdateCandidateComparator.INSTANCE.compare(website, candidate) > 0)) {
            candidate = website;
        }

        UpdateCandidate hash = resolveSingleByHash(identity, resolver);
        if (hash != null && isConfirmed(hash)) {
            return hash;
        }
        if (hash != null && (candidate == null || UpdateCandidateComparator.INSTANCE.compare(hash, candidate) > 0)) {
            candidate = hash;
        }

        boolean jarScan = plugin.getConfigManager().isJarScanEnabled();
        if (jarScan) {
            UpdateCandidate jar = resolveSingleFromJarReferences(identity, resolver);
            if (jar != null && isConfirmed(jar)) {
                return jar;
            }
            if (jar != null && (candidate == null || UpdateCandidateComparator.INSTANCE.compare(jar, candidate) > 0)) {
                candidate = jar;
            }
        }

        UpdateCandidate name = resolveSingleByName(identity, resolver);
        if (name != null) {
            if (candidate == null || UpdateCandidateComparator.INSTANCE.compare(name, candidate) > 0) {
                candidate = name;
            }
        }

        return candidate != null ? candidate : UpdateCandidate.noSource(identity);
    }

    public static boolean isConfirmed(UpdateCandidate c) {
        return c != null && c.confidence() == MatchConfidence.CONFIRMED
                && c.status() != UpdateStatus.NO_SOURCE
                && c.status() != UpdateStatus.NETWORK_ERROR
                && c.status() != UpdateStatus.RATE_LIMITED;
    }

    private @Nullable UpdateCandidate resolveSingleFromCatalog(PluginIdentity identity, VersionResolver resolver) {
        List<UpdateCandidate> candidates = new ArrayList<>();
        boolean blockedByLimit = false;

        for (UpdateSource source : sources) {
            SourceCatalog.CatalogSource entry = catalog.sourceFor(identity.mainClass(), identity.pluginName(), source.id());
            if (entry == null) continue;

            if (source instanceof GithubSource github && github.isRateLimited()) {
                blockedByLimit = true;
                continue;
            }

            UpdateSource.ProjectMatch match =
                    source.identifyFromCatalog(identity, entry.ref(), entry.options());
            if (match == null) continue;

            List<RemoteVersion> versions = source.listVersions(match);
            if (versions.isEmpty()) continue;

            UpdateCandidate candidate = resolver.resolve(identity, match, versions);
            if (candidate.status() != UpdateStatus.NO_SOURCE) {
                candidates.add(candidate);
            }
        }

        if (!candidates.isEmpty()) {
            candidates.sort(UpdateCandidateComparator.INSTANCE.reversed());
            return candidates.get(0);
        }
        if (blockedByLimit) {
            return UpdateCandidate.failed(identity, UpdateStatus.RATE_LIMITED);
        }
        return null;
    }

    private @Nullable UpdateCandidate resolveSingleFromWebsite(PluginIdentity identity, VersionResolver resolver) {
        String website = identity.website();
        if (website == null || website.isBlank()) {
            return null;
        }

        SourceUrlParser.ParseResult parsed = SourceUrlParser.parse(website);
        if (!parsed.success() || parsed.source() == null) {
            return null;
        }

        SourceCatalog.CatalogSource catalogSource = parsed.source();
        List<UpdateCandidate> candidates = new ArrayList<>();

        for (UpdateSource source : sources) {
            if (!source.id().equalsIgnoreCase(catalogSource.sourceId())) continue;
            if (source instanceof GithubSource github && github.isRateLimited()) continue;

            UpdateSource.ProjectMatch match;
            if (source instanceof GithubSource github && catalogSource.options() != null && "true".equals(catalogSource.options().get("ownerOnly"))) {
                match = github.identifyFromOwner(identity, catalogSource.ref());
            } else {
                match = source.identifyFromCatalog(identity, catalogSource.ref(), catalogSource.options() != null ? catalogSource.options() : Map.of());
            }
            if (match == null) continue;

            List<RemoteVersion> versions = source.listVersions(match);
            if (versions.isEmpty()) continue;

            UpdateCandidate candidate = resolver.resolve(identity, match, versions);
            if (candidate.status() != UpdateStatus.NO_SOURCE) {
                candidates.add(candidate);
            }
        }

        if (!candidates.isEmpty()) {
            candidates.sort(UpdateCandidateComparator.INSTANCE.reversed());
            return candidates.get(0);
        }
        return null;
    }

    private @Nullable UpdateCandidate resolveSingleByHash(PluginIdentity identity, VersionResolver resolver) {
        UpdateSource.ProjectMatch match = hangarSource.identifyBatch(List.of(identity)).get(identity.pluginName());
        if (match == null) {
            return null;
        }

        List<RemoteVersion> versions = hangarSource.listVersions(match);
        if (versions.isEmpty()) {
            return null;
        }

        UpdateCandidate candidate = resolver.resolve(identity, match, versions);
        return candidate.status() != UpdateStatus.NO_SOURCE ? candidate : null;
    }

    private @Nullable UpdateCandidate resolveSingleFromJarReferences(PluginIdentity identity, VersionResolver resolver) {
        List<JarScanner.DiscoveredRef> refs = jarScanner.scan(identity.jarFile(), identity.mainClass());
        if (refs.isEmpty()) {
            return null;
        }

        List<UpdateCandidate> candidates = new ArrayList<>();
        for (JarScanner.DiscoveredRef ref : jarScanner.orderedForLookup(refs)) {
            UpdateSource source = sourceById(ref.sourceId());
            if (source == null) {
                continue;
            }
            if (source instanceof GithubSource github && github.isRateLimited()) {
                continue;
            }
            if (!titleBelongsToPlugin(identity, source, ref.ref())) {
                continue;
            }

            UpdateSource.ProjectMatch base = source.identifyFromCatalog(identity, ref.ref(), Map.of());
            if (base == null) {
                continue;
            }
            UpdateSource.ProjectMatch match = new UpdateSource.ProjectMatch(
                    base.pluginName(),
                    base.projectRef(),
                    base.projectUrl(),
                    MatchConfidence.LIKELY,
                    MatchReason.JAR_REFERENCE,
                    base.knownVersionNumber()
            );

            List<RemoteVersion> versions = source.listVersions(match);
            if (versions.isEmpty()) {
                continue;
            }

            UpdateCandidate candidate = resolver.resolve(identity, match, versions);
            if (candidate.status() != UpdateStatus.NO_SOURCE) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(UpdateCandidateComparator.INSTANCE.reversed());
        return candidates.get(0);
    }

    private boolean titleBelongsToPlugin(PluginIdentity identity, UpdateSource source, String ref) {
        String slug = refTail(ref);
        double bySlug = PluginMatcher.resourceNameSimilarity(identity.pluginName(), slug);
        boolean slugCompanion = PluginMatcher.isCompanion(identity.pluginName(), slug);
        if (!slugCompanion && bySlug >= JAR_REF_MIN_SIMILARITY) {
            return true;
        }

        if (!slug.matches("^\\d+$") && bySlug < 0.40) {
            return false;
        }

        String title = source.projectTitle(ref);
        if (title == null || title.isBlank()) {
            Log.debug("updateservice.jarref-title-unknown", "source", source.id(), "ref", ref, "plugin", identity.pluginName());
            return false;
        }
        if (PluginMatcher.isCompanion(identity.pluginName(), title)) {
            Log.debug("updateservice.jarref-companion-rejected", "source", source.id(), "ref", ref, "plugin", identity.pluginName(), "title", title);
            return false;
        }
        double byTitle = PluginMatcher.resourceNameSimilarity(identity.pluginName(), title);
        if (byTitle < JAR_REF_MIN_SIMILARITY) {
            Log.debug("updateservice.jarref-low-similarity", "source", source.id(), "ref", ref, "plugin", identity.pluginName(), "title", title, "similarity", String.format(Locale.ROOT, "%.2f", byTitle));
            return false;
        }
        return true;
    }

    private static String refTail(@Nullable String ref) {
        if (ref == null) {
            return "";
        }
        int slash = ref.lastIndexOf('/');
        return slash >= 0 && slash + 1 < ref.length() ? ref.substring(slash + 1) : ref;
    }

    private @Nullable UpdateSource sourceById(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (UpdateSource source : sources) {
            if (id.equals(source.id())) {
                return source;
            }
        }
        return null;
    }

    private @Nullable UpdateCandidate resolveSingleByName(PluginIdentity identity, VersionResolver resolver) {
        List<UpdateCandidate> candidates = new ArrayList<>();

        for (UpdateSource source : sources) {
            UpdateSource.ProjectMatch match = source.identifyByName(identity);
            if (match == null) continue;

            List<RemoteVersion> versions = source.listVersions(match);
            if (versions.isEmpty()) continue;

            UpdateCandidate candidate = resolver.resolve(identity, match, versions);
            if (candidate.status() != UpdateStatus.NO_SOURCE) {
                candidates.add(candidate);
                if (candidate.confidence() == MatchConfidence.CONFIRMED
                        || (candidate.confidence() == MatchConfidence.LIKELY
                        && (candidate.status() == UpdateStatus.UPDATE_AVAILABLE || candidate.status() == UpdateStatus.UP_TO_DATE))) {
                    break;
                }
            }
        }

        if (!candidates.isEmpty()) {
            candidates.sort(UpdateCandidateComparator.INSTANCE.reversed());
            return candidates.get(0);
        }
        return null;
    }
}
