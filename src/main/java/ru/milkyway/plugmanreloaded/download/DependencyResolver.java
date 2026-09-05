package ru.milkyway.plugmanreloaded.download;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.download.models.DependencyTree;
import ru.milkyway.plugmanreloaded.download.models.SearchResultEntry;
import ru.milkyway.plugmanreloaded.update.NameUtil;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public class DependencyResolver {

    private static final double MIN_NAME_SIMILARITY = 0.90;

    private final PlugManReloaded plugin;
    private final PluginSearch searchEngine;
    private final SourceCatalog catalog;
    private final BiFunction<SearchResultEntry, Path, File> jarProvider;
    private final int maxDepth;

    public DependencyResolver(PlugManReloaded plugin, PluginSearch searchEngine, SourceCatalog catalog) {
        this(plugin, searchEngine, catalog, (BiFunction<SearchResultEntry, Path, File>) null);
    }

    public DependencyResolver(PlugManReloaded plugin, PluginSearch searchEngine, SourceCatalog catalog,
                              Function<SearchResultEntry, File> jarProvider) {
        this(plugin, searchEngine, catalog, jarProvider != null ? (entry, dir) -> jarProvider.apply(entry) : null);
    }

    public DependencyResolver(PlugManReloaded plugin, PluginSearch searchEngine, SourceCatalog catalog,
                              BiFunction<SearchResultEntry, Path, File> jarProvider) {
        this(plugin, searchEngine, catalog, jarProvider, plugin != null && plugin.getConfigManager() != null ? plugin.getConfigManager().getMaxDependencyDepth() : 5);
    }

    public DependencyResolver(PlugManReloaded plugin, PluginSearch searchEngine, SourceCatalog catalog,
                              Function<SearchResultEntry, File> jarProvider, int maxDepth) {
        this(plugin, searchEngine, catalog, jarProvider != null ? (entry, dir) -> jarProvider.apply(entry) : null, maxDepth);
    }

    public DependencyResolver(PlugManReloaded plugin, PluginSearch searchEngine, SourceCatalog catalog,
                              BiFunction<SearchResultEntry, Path, File> jarProvider, int maxDepth) {
        this.plugin = plugin;
        this.searchEngine = searchEngine;
        this.catalog = catalog;
        this.jarProvider = jarProvider;
        this.maxDepth = Math.max(0, maxDepth);
    }

    private static final class Accumulator {
        final List<String> alreadySatisfied = new ArrayList<>();
        final List<String> existingDisabledToEnable = new ArrayList<>();
        final List<String> existingUnloadedToLoad = new ArrayList<>();
        final List<SearchResultEntry> required = new ArrayList<>();
        final List<SearchResultEntry> optional = new ArrayList<>();
        final List<String> unresolvable = new ArrayList<>();
        final Set<String> processed = new HashSet<>();
        final Deque<String> path = new ArrayDeque<>();
        boolean hasCycles = false;
        String cycleDetails;
    }

    public DependencyTree resolve(File targetJar, SearchResultEntry targetEntry, boolean withSoftDeps) {
        return resolve(targetJar, targetEntry, withSoftDeps, null);
    }

    public DependencyTree resolve(File targetJar, SearchResultEntry targetEntry, boolean withSoftDeps, @Nullable Path stagingDir) {
        String targetName = targetEntry != null && targetEntry.title() != null && !targetEntry.title().isBlank()
                ? targetEntry.title()
                : (targetJar != null ? JarValidator.readPluginName(targetJar) : "Unknown");
        if (targetName == null) targetName = "Unknown";

        Accumulator acc = new Accumulator();
        acc.processed.add(targetName.toLowerCase(Locale.ROOT));
        acc.path.push(targetName.toLowerCase(Locale.ROOT));

        expand(targetJar, targetName, withSoftDeps, acc, 0, stagingDir);

        return new DependencyTree(
                targetName, targetEntry, acc.required, acc.optional, acc.alreadySatisfied,
                acc.existingDisabledToEnable, acc.existingUnloadedToLoad, acc.unresolvable,
                acc.hasCycles, acc.cycleDetails
        );
    }

    private void expand(@Nullable File jar, String ownerName, boolean withSoftDeps, Accumulator acc, int depth, @Nullable Path stagingDir) {
        if (jar == null) {
            if (depth == 0) {
                Log.debug("dependencyresolver.no-jar", "owner", ownerName);
            }
            return;
        }
        if (depth > maxDepth) {
            Log.debug("dependencyresolver.depth-limit", "maxDepth", String.valueOf(maxDepth), "owner", ownerName);
            return;
        }

        for (String dep : JarValidator.readDeclaredDependencies(jar)) {
            processDependency(dep, true, withSoftDeps, acc, depth, stagingDir);
        }
        if (withSoftDeps) {
            for (String dep : JarValidator.readDeclaredSoftDependencies(jar)) {
                processDependency(dep, false, withSoftDeps, acc, depth, stagingDir);
            }
        }
    }

    private void processDependency(@Nullable String depName, boolean isRequired, boolean withSoftDeps,
                                   Accumulator acc, int depth, @Nullable Path stagingDir) {
        if (depName == null || depName.isBlank()) return;
        String cleanDep = depName.trim();
        String lowerDep = cleanDep.toLowerCase(Locale.ROOT);

        if (acc.path.contains(lowerDep)) {
            acc.hasCycles = true;
            List<String> chain = new ArrayList<>(acc.path);
            Collections.reverse(chain);
            acc.cycleDetails = String.join(" -> ", chain) + " -> " + cleanDep;
            Log.debug("dependencyresolver.cycle-detected", "details", String.valueOf(acc.cycleDetails));
            return;
        }

        if (!acc.processed.add(lowerDep)) return;

        if (isSatisfiedOnServer(cleanDep)) {
            acc.alreadySatisfied.add(cleanDep);
            return;
        }

        Plugin loaded = Bukkit.getPluginManager().getPlugin(cleanDep);
        if (loaded != null && !loaded.isEnabled()) {
            acc.existingDisabledToEnable.add(loaded.getName());
            return;
        }

        File existingJar = null;
        try {
            existingJar = plugin.getPluginLifecycleManager().getJarIndex().find(cleanDep);
        } catch (Throwable t) {
            Log.debug("dependencyresolver.jar-not-in-index", t, "dependency", cleanDep);
        }

        if (existingJar != null && existingJar.isFile()) {
            acc.existingUnloadedToLoad.add(cleanDep);
            acc.path.push(lowerDep);
            expand(existingJar, cleanDep, withSoftDeps, acc, depth + 1, stagingDir);
            acc.path.pop();
            return;
        }

        SearchResultEntry entry = findDependencySource(cleanDep);
        if (entry == null) {
            if (isRequired) {
                acc.unresolvable.add(cleanDep);
            }
            return;
        }

        if (isRequired) {
            acc.required.add(entry);
        } else {
            acc.optional.add(entry);
        }

        if (depth + 1 > maxDepth || jarProvider == null) {
            return;
        }

        File depJar = null;
        try {
            depJar = jarProvider.apply(entry, stagingDir);
        } catch (Throwable t) {
            Log.debug("dependencyresolver.jar-fetch-failed", t, "dependency", cleanDep);
        }
        if (depJar == null) {
            return;
        }

        acc.path.push(lowerDep);
        expand(depJar, cleanDep, withSoftDeps, acc, depth + 1, stagingDir);
        acc.path.pop();
    }

    public boolean isSatisfiedOnServer(@Nullable String depName) {
        if (depName == null || depName.isBlank()) return false;
        String clean = depName.trim();

        Plugin p = Bukkit.getPluginManager().getPlugin(clean);
        if (p != null && p.isEnabled()) return true;

        for (Plugin active : Bukkit.getPluginManager().getPlugins()) {
            if (active == null || !active.isEnabled()) continue;
            try {
                List<String> provides = active.getDescription().getProvides();
                if (provides != null) {
                    for (String prov : provides) {
                        if (prov != null && prov.equalsIgnoreCase(clean)) return true;
                    }
                }
            } catch (Throwable t) {
                Log.debug("dependencyresolver.provides-read-failed", t, "plugin", active.getName());
            }
        }
        return false;
    }

    private static final List<String> SOURCE_TRUST_ORDER = List.of(
            "modrinth", "hangar", "direct", "github", "jenkins", "spigot", "spigot-premium", "ruspigot", "ruspigot-premium"
    );

    private static SourceCatalog.CatalogSource mostTrusted(List<SourceCatalog.CatalogSource> sources) {
        SourceCatalog.CatalogSource best = sources.get(0);
        int bestRank = SOURCE_TRUST_ORDER.indexOf(best.sourceId().toLowerCase(Locale.ROOT));
        for (SourceCatalog.CatalogSource candidate : sources) {
            int rank = SOURCE_TRUST_ORDER.indexOf(candidate.sourceId().toLowerCase(Locale.ROOT));
            if (rank >= 0 && (bestRank < 0 || rank < bestRank)) {
                best = candidate;
                bestRank = rank;
            }
        }
        return best;
    }

    private @Nullable SearchResultEntry findDependencySource(String depName) {
        if (catalog != null) {
            List<SourceCatalog.CatalogSource> list = catalog.lookupByName(depName);
            if (list != null && !list.isEmpty()) {
                SourceCatalog.CatalogSource cat = mostTrusted(list);
                boolean premium = cat.sourceId() != null && cat.sourceId().toLowerCase(Locale.ROOT).endsWith("-premium");
                return new SearchResultEntry(
                        cat.sourceId(), cat.ref(), depName, "Catalog", "", "",
                        cat.url() != null ? cat.url() : "", null, 1_000_000, 100, 100.0,
                        Collections.emptyList(), List.of("paper", "spigot"), Collections.emptyList(),
                        null, null, null, premium, !premium
                );
            }
        }

        if (searchEngine == null) {
            return null;
        }

        try {
            List<SearchResultEntry> searchHits = searchEngine.search(depName, null, 5);
            if (searchHits == null || searchHits.isEmpty()) {
                return null;
            }
            for (SearchResultEntry hit : searchHits) {
                if (nameBelongsToDependency(depName, hit)) {
                    return hit;
                }
            }
            Log.debug("dependencyresolver.no-name-match", "dependency", depName, "best", searchHits.get(0).title());
        } catch (Throwable t) {
            Log.debug("dependencyresolver.source-not-found", t, "dependency", depName);
        }
        return null;
    }

    private boolean nameBelongsToDependency(String depName, @Nullable SearchResultEntry hit) {
        if (hit == null || hit.title() == null) return false;
        if (NameUtil.isCompanion(depName, hit.title())) return false;
        if (NameUtil.resourceNameSimilarity(depName, hit.title()) >= MIN_NAME_SIMILARITY) return true;
        String slug = hit.projectId();
        return slug != null && !NameUtil.isCompanion(depName, slug)
                && NameUtil.resourceNameSimilarity(depName, slug) >= MIN_NAME_SIMILARITY;
    }
}

