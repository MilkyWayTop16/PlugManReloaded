package ru.milkyway.plugmanreloaded.update;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.UpdateModels.RemoteVersion;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateCandidate;
import ru.milkyway.plugmanreloaded.update.UpdateModels.UpdateStatus;
import ru.milkyway.plugmanreloaded.update.UpdateModels.InstallResult;
import ru.milkyway.plugmanreloaded.update.UpdateModels.InstallStatus;
import ru.milkyway.plugmanreloaded.update.install.UpdateInstaller;
import ru.milkyway.plugmanreloaded.update.source.DirectSource;
import ru.milkyway.plugmanreloaded.update.source.GithubSource;
import ru.milkyway.plugmanreloaded.update.source.HangarSource;
import ru.milkyway.plugmanreloaded.update.source.JenkinsSource;
import ru.milkyway.plugmanreloaded.update.source.ModrinthSource;
import ru.milkyway.plugmanreloaded.update.source.RusPigotSource;
import ru.milkyway.plugmanreloaded.update.source.SpigotSource;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;
import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

public final class UpdateService {

    private final PlugManReloaded plugin;
    private final UpdateCache cache;
    private final List<UpdateSource> sources = new ArrayList<>();
    private final HangarSource hangarSource;
    private final GithubSource githubSource;
    private final UpdateInstaller installer;
    @Getter private volatile SourceCatalog catalog;

    @Getter private final IdentityScanner identityScanner;
    private final UpdatePipeline pipeline;
    @Getter private final UpdateNotifications notifications;

    private static final int RESOLVE_THREADS = 16;
    private final ExecutorService resolveExecutor = Executors.newFixedThreadPool(
            RESOLVE_THREADS,
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "PlugManReloaded-update-" + counter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );

    private static final Object MISSES_SAVE_LOCK = new Object();

    @Getter private volatile int lastAvailableCount = 0;
    @Getter private volatile int lastTotalCount = 0;
    @Getter private volatile boolean initialChecked = false;
    private volatile List<UpdateCandidate> lastAllResults = null;
    private volatile long lastAllResultsTime = 0L;
    private static final long RECENT_ALL_TTL_MS = 60_000L;

    public UpdateService(PlugManReloaded plugin) {
        this.plugin = plugin;
        this.cache = new UpdateCache(plugin.getConfigManager().getUpdateCacheTtlHours() * 3600_000L);

        String version = PluginMetaHelper.getVersion(plugin);
        String userAgent = "PlugManReloaded/" + version + " (+https://github.com/MilkyWayTop16/PlugManReloaded)";
        HttpJson.setUserAgent(userAgent);

        cache.loadMisses(missesCacheFile());

        this.catalog = new SourceCatalog(userCatalogFile(), language());
        this.hangarSource = new HangarSource(cache);
        String githubToken = plugin.getConfigManager().getGithubToken();
        this.githubSource = new GithubSource(cache, githubToken);
        
        this.sources.add(new ModrinthSource(cache));
        this.sources.add(hangarSource);
        this.sources.add(new DirectSource(cache));
        this.sources.add(githubSource);
        this.sources.add(new JenkinsSource(cache));
        this.sources.add(new SpigotSource(cache));
        this.sources.add(new RusPigotSource(cache, githubSource));
        
        this.installer = new UpdateInstaller(plugin, userAgent);
        
        this.identityScanner = new IdentityScanner(plugin);
        this.pipeline = new UpdatePipeline(plugin, sources, hangarSource, new JarScanner(), catalog);
        this.notifications = new UpdateNotifications(plugin, this);
    }

    public void install(UpdateCandidate candidate, Consumer<InstallResult> callback) {
        install(candidate, true, callback);
    }

    public void install(UpdateCandidate candidate, boolean restartDependents, Consumer<InstallResult> callback) {
        installer.install(candidate, restartDependents, result -> {
            if (result.outcome() == InstallStatus.INSTALLED || result.outcome() == InstallStatus.PENDING_RESTART) {
                if (candidate != null && candidate.version() != null) {
                    cache.invalidateVersions(candidate.version().projectRef());
                }
                synchronized (this) {
                    lastAllResults = null;
                    if (lastAvailableCount > 0) {
                        lastAvailableCount--;
                    }
                }
            }
            callback.accept(result);
        });
    }

    public List<UpdateCandidate> getRecentAllResults() {
        List<UpdateCandidate> current = lastAllResults;
        if (current != null && (System.currentTimeMillis() - lastAllResultsTime < RECENT_ALL_TTL_MS)) {
            return Collections.unmodifiableList(current);
        }
        return null;
    }

    public List<UpdateCandidate> getLastResults() {
        List<UpdateCandidate> current = lastAllResults;
        return current != null ? Collections.unmodifiableList(current) : null;
    }

    public void clearVersionsCache() {
        cache.clearVersions();
        cache.clearMisses();
        synchronized (this) {
            lastAllResults = null;
            lastAvailableCount = 0;
            lastTotalCount = 0;
            initialChecked = false;
        }
    }

    public void reload() {
        cache.setTtlMillis(plugin.getConfigManager().getUpdateCacheTtlHours() * 3600_000L);
        this.catalog = new SourceCatalog(userCatalogFile(), language());
    }

    private String language() {
        return plugin.getConfigManager().getMainConfig().getLanguage();
    }

    public UpdateSource getSource(String sourceId) {
        if (sourceId == null) return null;
        for (UpdateSource source : sources) {
            if (source.id().equalsIgnoreCase(sourceId)) {
                return source;
            }
        }
        return null;
    }

    public List<Plugin> snapshotLoadedPlugins() {
        if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null) {
            return Collections.emptyList();
        }
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        if (plugins == null || plugins.length == 0) {
            return Collections.emptyList();
        }
        List<Plugin> list = new ArrayList<>(plugins.length);
        for (Plugin p : plugins) {
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private File userCatalogFile() {
        return SourceCatalog.resolveFile(plugin);
    }

    private File missesCacheFile() {
        return new File(plugin.getDataFolder(), "update-cache-misses.txt");
    }

    public void checkAll(Consumer<List<UpdateCandidate>> callback) {
        checkAll(plugin.getConfigManager().isAllowPrerelease(), callback);
    }

    public void checkAll(boolean allowPrerelease, Consumer<List<UpdateCandidate>> callback) {
        List<Plugin> loadedPlugins = snapshotLoadedPlugins();
        TaskScheduler.runAsync(plugin, () -> runCheck(identityScanner.scanAllIdentities(loadedPlugins), allowPrerelease, callback));
    }

    public @Nullable UpdateCandidate checkSync(Plugin target, boolean allowPrerelease) {
        if (target == null) return null;
        PluginIdentity identity = identityScanner.scanIdentity(target);
        if (identity == null) return null;
        List<UpdateCandidate> results = check(List.of(identity), allowPrerelease);
        return !results.isEmpty() ? results.get(0) : null;
    }

    public void checkOne(Plugin target, Consumer<List<UpdateCandidate>> callback) {
        checkOne(target, plugin.getConfigManager().isAllowPrerelease(), callback);
    }

    public void checkOne(Plugin target, boolean allowPrerelease, Consumer<List<UpdateCandidate>> callback) {
        if (target == null) {
            TaskScheduler.runSync(plugin, () -> callback.accept(Collections.emptyList()));
            return;
        }
        cache.invalidateMiss(target.getName());
        TaskScheduler.runAsync(plugin, () -> {
            PluginIdentity identity = identityScanner.scanIdentity(target);
            if (identity == null) {
                TaskScheduler.runSync(plugin, () -> callback.accept(Collections.emptyList()));
                return;
            }
            runCheck(List.of(identity), allowPrerelease, callback);
        });
    }

    public void checkOne(File jar, Consumer<List<UpdateCandidate>> callback) {
        checkOne(jar, plugin.getConfigManager().isAllowPrerelease(), callback);
    }

    public void checkOne(File jar, boolean allowPrerelease, Consumer<List<UpdateCandidate>> callback) {
        if (jar == null || !jar.exists()) {
            TaskScheduler.runSync(plugin, () -> callback.accept(Collections.emptyList()));
            return;
        }
        TaskScheduler.runAsync(plugin, () -> {
            PluginIdentity identity = identityScanner.scanIdentity(jar);
            if (identity != null) {
                cache.invalidateMiss(identity.pluginName());
                runCheck(List.of(identity), allowPrerelease, callback);
            } else {
                TaskScheduler.runSync(plugin, () -> callback.accept(Collections.emptyList()));
            }
        });
    }

    private void runCheck(List<PluginIdentity> identities, boolean allowPrerelease, Consumer<List<UpdateCandidate>> callback) {
        if (identities.isEmpty()) {
            TaskScheduler.runSync(plugin, () -> callback.accept(Collections.emptyList()));
            return;
        }

        long startTime = System.currentTimeMillis();
        if (identities.size() > 1) {
            Log.debug("updateservice.check-start", "count", String.valueOf(identities.size()));
        }

        List<UpdateCandidate> results = new ArrayList<>(identities.size());
        try {
            results.addAll(check(identities, allowPrerelease));
        } catch (Exception | LinkageError t) {
            Log.warn("updateservice.check-error", t, "error", t.getMessage());
            for (PluginIdentity identity : identities) {
                results.add(UpdateCandidate.failed(identity, UpdateStatus.NETWORK_ERROR));
            }
        }
        synchronized (MISSES_SAVE_LOCK) {
            cache.saveMisses(missesCacheFile());
        }
        cache.sweepExpired();

        long elapsed = System.currentTimeMillis() - startTime;
        long available = 0;
        for (UpdateCandidate c : results) {
            if (c.status().hasNewerVersion()) available++;
        }
        
        recordCheckResults(results, available);
        if (identities.size() > 1) {
            Log.info("updateservice.check-finished", "count", String.valueOf(identities.size()), "available", String.valueOf(available), "elapsed", String.valueOf(elapsed));
        }

        TaskScheduler.runSync(plugin, () -> callback.accept(results));
    }

    void recordCheckResults(List<UpdateCandidate> results, long available) {
        synchronized (this) {
            initialChecked = true;

            if (results.size() > 1) {
                lastAllResults = Collections.unmodifiableList(new ArrayList<>(results));
                lastAllResultsTime = System.currentTimeMillis();
                lastAvailableCount = (int) available;
                lastTotalCount = results.size();
            } else if (!results.isEmpty() && lastAllResults != null) {
                UpdateCandidate single = results.get(0);
                List<UpdateCandidate> copy = new ArrayList<>(lastAllResults);
                boolean replaced = false;
                for (int i = 0; i < copy.size(); i++) {
                    if (copy.get(i).identity().pluginName().equalsIgnoreCase(single.identity().pluginName())) {
                        copy.set(i, single);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    copy.add(single);
                }
                long newAvail = 0;
                for (UpdateCandidate c : copy) {
                    if (c.status().hasNewerVersion()) newAvail++;
                }
                lastAvailableCount = (int) newAvail;
                lastAllResults = Collections.unmodifiableList(copy);
            } else if (available > 0 && lastAvailableCount == 0) {
                lastAvailableCount = (int) available;
            }
        }
    }

    private List<UpdateCandidate> check(List<PluginIdentity> identities) {
        return check(identities, plugin.getConfigManager().isAllowPrerelease());
    }

    private List<UpdateCandidate> check(List<PluginIdentity> identities, boolean allowPrerelease) {
        ServerProfile profile = ServerProfile.detect();
        VersionResolver resolver = new VersionResolver(profile, allowPrerelease);

        Map<String, List<UpdateCandidate>> candidatesByPlugin = new HashMap<>();

        for (UpdateSource source : sources) {
            if (source == hangarSource) continue;

            Map<String, UpdateSource.ProjectMatch> matches = source.identifyBatch(identities);
            if (matches.isEmpty()) continue;

            List<PluginIdentity> matchedIdentities = new ArrayList<>();
            for (PluginIdentity id : identities) {
                if (matches.containsKey(id.pluginName())) {
                    matchedIdentities.add(id);
                }
            }

            List<UpdateCandidate> batchCandidates = new ArrayList<>();
            resolveInParallel(matchedIdentities, batchCandidates, identity -> {
                UpdateSource.ProjectMatch match = matches.get(identity.pluginName());
                if (match == null) return null;
                List<RemoteVersion> versions = source.listVersions(match);
                if (versions.isEmpty()) return null;
                UpdateCandidate candidate = resolver.resolve(identity, match, versions);
                return candidate.status() != UpdateStatus.NO_SOURCE ? candidate : null;
            });

            for (UpdateCandidate c : batchCandidates) {
                candidatesByPlugin.computeIfAbsent(c.identity().pluginName(), k -> new ArrayList<>()).add(c);
            }
        }

        List<PluginIdentity> stillNeeded = withoutConfirmedSource(identities, candidatesByPlugin);
        if (!stillNeeded.isEmpty()) {
            List<UpdateCandidate> pipelineResults = new ArrayList<>();
            resolveInParallel(stillNeeded, pipelineResults, identity -> pipeline.resolvePipeline(identity, resolver));
            for (UpdateCandidate c : pipelineResults) {
                if (c != null && c.status() != UpdateStatus.NO_SOURCE) {
                    candidatesByPlugin.computeIfAbsent(c.identity().pluginName(), k -> new ArrayList<>()).add(c);
                }
            }
        }

        List<UpdateCandidate> results = new ArrayList<>(identities.size());
        for (PluginIdentity identity : identities) {
            List<UpdateCandidate> list = candidatesByPlugin.get(identity.pluginName());
            if (list != null && !list.isEmpty()) {
                list.sort(UpdateCandidateComparator.INSTANCE.reversed());
                results.add(list.get(0));
            } else {
                results.add(UpdateCandidate.noSource(identity));
            }
        }
        return results;
    }

    public boolean isGithubRateLimited() {
        return githubSource.isRateLimited();
    }

    private static boolean hasConfirmedSource(List<UpdateCandidate> candidates) {
        if (candidates == null) return false;
        for (UpdateCandidate c : candidates) {
            if (UpdatePipeline.isConfirmed(c)) {
                return true;
            }
        }
        return false;
    }

    private static List<PluginIdentity> withoutConfirmedSource(List<PluginIdentity> identities,
                                                               Map<String, List<UpdateCandidate>> candidatesByPlugin) {
        List<PluginIdentity> result = new ArrayList<>();
        for (PluginIdentity identity : identities) {
            if (!hasConfirmedSource(candidatesByPlugin.get(identity.pluginName()))) {
                result.add(identity);
            }
        }
        return result;
    }

    List<PluginIdentity> resolveInParallel(List<PluginIdentity> pending,
                                           List<UpdateCandidate> results,
                                           Function<PluginIdentity, UpdateCandidate> attempt) {
        if (pending.isEmpty()) {
            return pending;
        }
        if (pending.size() == 1) {
            UpdateCandidate single = attempt.apply(pending.get(0));
            if (single == null) {
                return pending;
            }
            results.add(single);
            return List.of();
        }

        if (resolveExecutor == null || resolveExecutor.isShutdown()) {
            return pending;
        }

        List<Future<UpdateCandidate>> futures = new ArrayList<>(pending.size());
        for (PluginIdentity identity : pending) {
            try {
                futures.add(resolveExecutor.submit(() -> attempt.apply(identity)));
            } catch (RejectedExecutionException e) {
                futures.add(null);
            }
        }

        List<PluginIdentity> stillPending = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            Future<UpdateCandidate> future = futures.get(i);
            if (future == null) {
                stillPending.add(pending.get(i));
                continue;
            }
            UpdateCandidate candidate = null;
            try {
                candidate = future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.debug("updateservice.check-interrupted", interrupted, "plugin", pending.get(i).pluginName());
                for (int j = i; j < futures.size(); j++) {
                    Future<UpdateCandidate> remaining = futures.get(j);
                    if (remaining != null) {
                        remaining.cancel(true);
                    }
                    stillPending.add(pending.get(j));
                }
                break;
            } catch (Exception | LinkageError t) {
                Log.debug("updateservice.check-failed", t, "plugin", pending.get(i).pluginName());
            }
            if (candidate != null) {
                results.add(candidate);
            } else {
                stillPending.add(pending.get(i));
            }
        }
        return stillPending;
    }

    public ServerProfile getServerProfile() {
        return ServerProfile.detect();
    }

    public UpdateCache getUpdateCache() {
        return cache;
    }

    public void shutdown() {
        if (resolveExecutor != null && !resolveExecutor.isShutdown()) {
            resolveExecutor.shutdownNow();
            try {
                resolveExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean isShutdown() {
        return resolveExecutor == null || resolveExecutor.isShutdown();
    }

    ExecutorService getResolveExecutor() {
        return resolveExecutor;
    }
}
