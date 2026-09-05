package ru.milkyway.plugmanreloaded.update;

import ru.milkyway.plugmanreloaded.update.UpdateModels.*;
import ru.milkyway.plugmanreloaded.update.VersionUtil.Resolver;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.UpdateInfo;
import ru.milkyway.plugmanreloaded.api.event.PluginUpdateFoundEvent;
import ru.milkyway.plugmanreloaded.update.input.SourceUrlParser;
import ru.milkyway.plugmanreloaded.update.install.UpdateInstaller;
import ru.milkyway.plugmanreloaded.update.source.DirectSource;
import ru.milkyway.plugmanreloaded.update.source.GithubSource;
import ru.milkyway.plugmanreloaded.update.source.JenkinsSource;
import ru.milkyway.plugmanreloaded.update.source.HangarSource;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

public final class UpdateService implements Listener {

    private final PlugManReloaded plugin;
    private final java.util.Map<String, String[]> hashCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final UpdateCache cache;
    private final List<UpdateSource> sources = new ArrayList<>();
    private final HangarSource hangarSource;
    private final GithubSource githubSource;
    private final UpdateInstaller installer;
    private final JarScanner jarScanner = new JarScanner();
    private volatile SourceCatalog catalog;

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

    private static final double JAR_REF_MIN_SIMILARITY = 0.90;

    private static final Object MISSES_SAVE_LOCK = new Object();

    private volatile int lastAvailableCount = 0;
    private volatile int lastTotalCount = 0;
    private volatile boolean initialChecked = false;
    private volatile List<UpdateCandidate> lastAllResults = null;
    private volatile long lastAllResultsTime = 0L;
    private static final long RECENT_ALL_TTL_MS = 60_000L;

    public UpdateService(PlugManReloaded plugin) {
        this.plugin = plugin;
                this.cache = new UpdateCache(plugin.getConfigManager().getUpdateCacheTtlHours() * 3600_000L);

        String version = PluginMetaHelper.getVersion(plugin);
        String userAgent = "PlugManReloaded/" + version
                + " (+https://github.com/MilkyWayTop16/PlugManReloaded)";
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
                lastAllResults = null;
                if (lastAvailableCount > 0) {
                    lastAvailableCount--;
                }
            }
            callback.accept(result);
        });
    }

    public @Nullable List<UpdateCandidate> getRecentAllResults() {
        if (lastAllResults != null && (System.currentTimeMillis() - lastAllResultsTime < RECENT_ALL_TTL_MS)) {
            return Collections.unmodifiableList(lastAllResults);
        }
        return null;
    }

    public void clearVersionsCache() {
        cache.clearVersions();
        cache.clearMisses();
        lastAllResults = null;
        lastAvailableCount = 0;
        lastTotalCount = 0;
        initialChecked = false;
    }

    public void reload() {
        cache.setTtlMillis(plugin.getConfigManager().getUpdateCacheTtlHours() * 3600_000L);
        this.catalog = new SourceCatalog(userCatalogFile(), language());
    }

    private String language() {
        return plugin.getConfigManager().getMainConfig().getLanguage();
    }

    public @Nullable UpdateSource getSource(@Nullable String sourceId) {
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
        return java.util.Arrays.stream(plugins).filter(java.util.Objects::nonNull).toList();
    }

    public List<PluginIdentity> scanAllIdentities(List<Plugin> plugins) {
        List<PluginIdentity> identities = new ArrayList<>();
        Map<String, PluginIdentity> byName = new java.util.LinkedHashMap<>();
        if (plugins != null) {
            for (Plugin target : plugins) {
                PluginIdentity identity = scanIdentity(target);
                if (identity != null) {
                    byName.put(identity.pluginName().toLowerCase(Locale.ROOT), identity);
                }
            }
        }

        for (ru.milkyway.plugmanreloaded.managers.PluginJarIndex.JarInfo info : plugin.getPluginLifecycleManager().getJarIndex().getEntries()) {
            if (info.file() != null && info.file().isFile()) {
                String key = info.preferredName().toLowerCase(Locale.ROOT);
                if (!byName.containsKey(key)) {
                    PluginIdentity fileIdentity = scanIdentity(info.file());
                    if (fileIdentity != null) {
                        byName.put(key, fileIdentity);
                    }
                }
            }
        }

        identities.addAll(byName.values());
        return identities;
    }

    public @Nullable PluginIdentity scanIdentity(@Nullable Plugin target) {
        if (target == null) return null;

        File jar = plugin.getPluginLifecycleManager().getPluginFile(target);
        if (jar == null || !jar.isFile()) {
            jar = plugin.getPluginLifecycleManager().getJarIndex().find(target.getName());
        }
        if (jar == null || !jar.isFile()) {
            Log.debug("identityscanner.jar-not-found", "plugin", target.getName());
            return null;
        }

        String[] hashes = hash(jar);
        String pendingVersion = findPendingUpdateVersion(target.getName(), jar);
        PluginEdition edition = PluginMatcher.detectEdition(
                jar,
                target.getName(),
                target.getDescription().getVersion(),
                target.getDescription().getMain(),
                target.getDescription().getWebsite()
        );

        return new PluginIdentity(
                target.getName(),
                target.getDescription().getMain(),
                target.getDescription().getVersion(),
                target.getDescription().getAuthors(),
                target.getDescription().getWebsite(),
                hashes[0],
                hashes[1],
                jar,
                pendingVersion,
                edition
        );
    }

    public @Nullable PluginIdentity scanIdentity(@Nullable File jar) {
        if (jar == null || !jar.isFile()) return null;

        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar)) {
            java.util.jar.JarEntry entry = jarFile.getJarEntry("plugin.yml");
            if (entry == null) entry = jarFile.getJarEntry("paper-plugin.yml");
            if (entry == null) return null;

            try (java.io.Reader reader = new java.io.InputStreamReader(jarFile.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) {
                org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
                String name = yaml.getString("name");
                if (name == null || name.isBlank()) return null;
                String main = yaml.getString("main");
                String version = yaml.getString("version", "1.0");
                List<String> authors = yaml.getStringList("authors");
                if (authors.isEmpty()) {
                    String single = yaml.getString("author");
                    if (single != null && !single.isBlank()) {
                        authors = List.of(single);
                    }
                }
                String website = yaml.getString("website");

                String[] hashes = hash(jar);
                String pendingVersion = findPendingUpdateVersion(name.trim(), jar);
                PluginEdition edition = PluginMatcher.detectEdition(
                        jar,
                        name.trim(),
                        version,
                        main,
                        website
                );

                return new PluginIdentity(
                        name.trim(),
                        main,
                        version,
                        authors,
                        website,
                        hashes[0],
                        hashes[1],
                        jar,
                        pendingVersion,
                        edition
                );
            }
        } catch (Throwable t) {
            Log.debug("identityscanner.descriptor-read-failed", t, "file", jar.getName());
            return null;
        }
    }

    private @Nullable String findPendingUpdateVersion(String pluginName, File jar) {
        try {
            File updateFolder = Bukkit.getUpdateFolderFile();
            if (updateFolder == null || !updateFolder.isDirectory()) {
                if (jar != null && jar.getParentFile() != null) {
                    File alt = new File(jar.getParentFile(), "update");
                    if (alt.isDirectory()) {
                        updateFolder = alt;
                    }
                }
            }
            if (updateFolder == null || !updateFolder.isDirectory()) {
                return null;
            }

            File directPending = new File(updateFolder, jar.getName());
            if (directPending.isFile()) {
                ru.milkyway.plugmanreloaded.managers.PluginJarIndex.JarDescriptor desc =
                        ru.milkyway.plugmanreloaded.managers.PluginJarIndex.readDescriptor(directPending);
                if (desc != null && desc.version() != null && !desc.version().isBlank()) {
                    return desc.version();
                }
            }

            File[] files = updateFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (files != null) {
                for (File f : files) {
                    ru.milkyway.plugmanreloaded.managers.PluginJarIndex.JarDescriptor desc =
                            ru.milkyway.plugmanreloaded.managers.PluginJarIndex.readDescriptor(f);
                    if (desc != null && desc.declaredName() != null && desc.declaredName().equalsIgnoreCase(pluginName)) {
                        if (desc.version() != null && !desc.version().isBlank()) {
                            return desc.version();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("identityscanner.pending-update-check-failed", t, "plugin", pluginName);
        }
        return null;
    }

    private String[] hash(File jar) {
        try {
            String cacheKey = jar.getCanonicalPath() + ":" + jar.length() + ":" + jar.lastModified();
            String[] cached = hashCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");

            try (java.io.InputStream raw = java.nio.file.Files.newInputStream(jar.toPath());
                 java.security.DigestInputStream first = new java.security.DigestInputStream(raw, sha1);
                 java.security.DigestInputStream second = new java.security.DigestInputStream(first, sha256)) {
                byte[] buffer = new byte[16384];
                while (second.read(buffer) != -1) {
                    continue;
                }
            }

            String[] result = new String[]{PluginMatcher.toHex(sha1.digest()), PluginMatcher.toHex(sha256.digest())};
            hashCache.put(cacheKey, result);
            return result;
        } catch (Throwable t) {
            Log.debug("identityscanner.hash-failed", t, "file", jar.getName());
            return new String[]{null, null};
        }
    }

    public UpdateService getIdentityScanner() {
        return this;
    }

    public @Nullable PluginIdentity collect(@Nullable Plugin target) {
        return scanIdentity(target);
    }

    public @Nullable PluginIdentity collect(@Nullable File jar) {
        return scanIdentity(jar);
    }

    public List<PluginIdentity> collectAll(List<Plugin> plugins) {
        return scanAllIdentities(plugins);
    }


    private File userCatalogFile() {
        return SourceCatalog.resolveFile(plugin);
    }

    private File missesCacheFile() {
        return new File(plugin.getDataFolder(), "update-cache-misses.txt");
    }

    public void checkOnStartIfEnabled() {
        if (!plugin.getConfigManager().isUpdatesCheckOnStart()) {
            return;
        }

        checkAll(results -> {
            int available = 0;
            for (UpdateCandidate candidate : results) {
                if (candidate.status().hasNewerVersion()) {
                    available++;
                    if (Bukkit.getServer() != null) {
                        Plugin matchedPlugin = Bukkit.getPluginManager().getPlugin(candidate.identity().pluginName());
                        if (matchedPlugin != null) {
                            Bukkit.getPluginManager().callEvent(new PluginUpdateFoundEvent(
                                    matchedPlugin, UpdateInfo.from(candidate)));
                        }
                    }
                }
            }
            lastAvailableCount = available;
            lastTotalCount = results.size();
            initialChecked = true;

            if (available > 0) {
                String mode = plugin.getConfigManager().getUpdatesNotifyMode().toLowerCase(Locale.ROOT);
                if (mode.equals("on-start") || mode.equals("both")) {
                    dispatchNotification(available, results.size());
                }
            }
        });
    }

    private void dispatchNotification(int available, int total) {
        String target = plugin.getConfigManager().getUpdatesNotifyTarget().toLowerCase(Locale.ROOT);
        Map<String, String> placeholders = Map.of(
                "count", String.valueOf(available),
                "available", String.valueOf(available),
                "total", String.valueOf(total)
        );

        if (target.equals("console") || target.equals("all") || target.equals("both")) {
            plugin.getConfigManager().executeActions(Bukkit.getConsoleSender(), "update.plugins-notify-console", placeholders);
        }

        if (target.equals("players") || target.equals("all") || target.equals("both")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (canReceiveNotify(player)) {
                    plugin.getConfigManager().executeActions(player, "update.plugins-notify", placeholders);
                }
            }
        }
    }

    private boolean canReceiveNotify(Player player) {
        return player != null && player.isOnline() && (
                player.hasPermission("plugmanreloaded.admin")
                || player.hasPermission("plugmanreloaded.notify")
                || player.isOp()
        );
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isUpdatesCheckOnStart()) return;

        String mode = plugin.getConfigManager().getUpdatesNotifyMode().toLowerCase(Locale.ROOT);
        if (!mode.equals("on-join") && !mode.equals("both")) return;

        String target = plugin.getConfigManager().getUpdatesNotifyTarget().toLowerCase(Locale.ROOT);
        if (target.equals("console")) return;

        if (!initialChecked || lastAvailableCount <= 0) return;

        Player player = event.getPlayer();
        if (!canReceiveNotify(player)) return;

        TaskScheduler.runSyncLater(plugin, () -> {
            if (player.isOnline()) {
                Map<String, String> placeholders = Map.of(
                        "count", String.valueOf(lastAvailableCount),
                        "available", String.valueOf(lastAvailableCount),
                        "total", String.valueOf(lastTotalCount)
                );
                plugin.getConfigManager().executeActions(player, "update.plugins-notify", placeholders);
            }
        }, 40L);
    }

    public void checkAll(Consumer<List<UpdateCandidate>> callback) {
        List<Plugin> loadedPlugins = snapshotLoadedPlugins();
        TaskScheduler.runAsync(plugin, () -> runCheck(scanAllIdentities(loadedPlugins), callback));
    }

    public void checkOne(Plugin target, Consumer<List<UpdateCandidate>> callback) {
        if (target != null) {
            cache.invalidateMiss(target.getName());
        }
        TaskScheduler.runAsync(plugin, () -> {
            PluginIdentity identity = scanIdentity(target);
            if (identity == null) {
                TaskScheduler.runSync(plugin, () -> callback.accept(Collections.emptyList()));
                return;
            }
            runCheck(List.of(identity), callback);
        });
    }

    public void checkOne(File jar, Consumer<List<UpdateCandidate>> callback) {
        TaskScheduler.runAsync(plugin, () -> {
            PluginIdentity identity = scanIdentity(jar);
            if (identity != null) {
                cache.invalidateMiss(identity.pluginName());
                runCheck(List.of(identity), callback);
            } else {
                TaskScheduler.runSync(plugin, () -> callback.accept(Collections.emptyList()));
            }
        });
    }

    private void runCheck(List<PluginIdentity> identities, Consumer<List<UpdateCandidate>> callback) {
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
            results.addAll(check(identities));
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
        long available = results.stream().filter(c -> c.status().hasNewerVersion()).count();
        recordCheckResults(identities, results, available);
        if (identities.size() > 1) {
            Log.info("updateservice.check-finished", "count", String.valueOf(identities.size()), "available", String.valueOf(available), "elapsed", String.valueOf(elapsed));
        }

        TaskScheduler.runSync(plugin, () -> callback.accept(results));
    }

    void recordCheckResults(List<PluginIdentity> identities, List<UpdateCandidate> results, long available) {
        initialChecked = true;

        if (identities.size() > 1) {
            lastAllResults = new ArrayList<>(results);
            lastAllResultsTime = System.currentTimeMillis();
            lastAvailableCount = (int) available;
            lastTotalCount = results.size();
        } else if (!results.isEmpty() && lastAllResults != null) {
            UpdateCandidate single = results.get(0);
            for (int i = 0; i < lastAllResults.size(); i++) {
                if (lastAllResults.get(i).identity().pluginName().equalsIgnoreCase(single.identity().pluginName())) {
                    lastAllResults.set(i, single);
                    break;
                }
            }
            lastAvailableCount = (int) lastAllResults.stream().filter(c -> c.status().hasNewerVersion()).count();
        } else if (available > 0 && lastAvailableCount == 0) {
            lastAvailableCount = (int) available;
        }
    }

    private @Nullable List<UpdateCandidate> check(List<PluginIdentity> identities) {
        ServerProfile profile = ServerProfile.detect();
        boolean allowPrerelease = plugin.getConfigManager().isAllowPrerelease();
        Resolver resolver = new VersionUtil.Resolver(profile, allowPrerelease);

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
            resolveInParallel(stillNeeded, pipelineResults, identity -> resolvePipeline(identity, resolver));
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
                list.sort(CANDIDATE_COMPARATOR.reversed());
                results.add(list.get(0));
            } else {
                results.add(UpdateCandidate.noSource(identity));
            }
        }
        return results;
    }

    private UpdateCandidate resolvePipeline(PluginIdentity identity, Resolver resolver) {
        UpdateCandidate candidate = resolveSingleFromCatalog(identity, resolver);
        if (candidate != null && isConfirmed(candidate)) {
            return candidate;
        }

        UpdateCandidate website = resolveSingleFromWebsite(identity, resolver);
        if (website != null && isConfirmed(website)) {
            return website;
        }
        if (website != null && (candidate == null || CANDIDATE_COMPARATOR.compare(website, candidate) > 0)) {
            candidate = website;
        }

        UpdateCandidate hash = resolveSingleByHash(identity, resolver);
        if (hash != null && isConfirmed(hash)) {
            return hash;
        }
        if (hash != null && (candidate == null || CANDIDATE_COMPARATOR.compare(hash, candidate) > 0)) {
            candidate = hash;
        }

        boolean jarScan = plugin.getConfigManager().isJarScanEnabled();
        if (jarScan) {
            UpdateCandidate jar = resolveSingleFromJarReferences(identity, resolver);
            if (jar != null && isConfirmed(jar)) {
                return jar;
            }
            if (jar != null && (candidate == null || CANDIDATE_COMPARATOR.compare(jar, candidate) > 0)) {
                candidate = jar;
            }
        }

        UpdateCandidate name = resolveSingleByName(identity, resolver);
        if (name != null) {
            if (candidate == null || CANDIDATE_COMPARATOR.compare(name, candidate) > 0) {
                candidate = name;
            }
        }

        return candidate != null ? candidate : UpdateCandidate.noSource(identity);
    }

    private static boolean isConfirmed(UpdateCandidate c) {
        return c != null && c.confidence() == MatchConfidence.CONFIRMED
                && c.status() != UpdateStatus.NO_SOURCE
                && c.status() != UpdateStatus.NETWORK_ERROR
                && c.status() != UpdateStatus.RATE_LIMITED;
    }

    public boolean isGithubRateLimited() {
        return githubSource.isRateLimited();
    }

    private static boolean hasConfirmedSource(@Nullable List<UpdateCandidate> candidates) {
        if (candidates == null) return false;
        for (UpdateCandidate c : candidates) {
            if (isConfirmed(c)) {
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

    private @Nullable UpdateCandidate resolveSingleFromWebsite(PluginIdentity identity, Resolver resolver) {
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
            candidates.sort(CANDIDATE_COMPARATOR.reversed());
            return candidates.get(0);
        }
        return null;
    }

    private @Nullable UpdateCandidate resolveSingleByHash(PluginIdentity identity, Resolver resolver) {
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

    private @Nullable UpdateCandidate resolveSingleFromJarReferences(PluginIdentity identity, Resolver resolver) {
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
        candidates.sort(CANDIDATE_COMPARATOR.reversed());
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

    private @Nullable UpdateCandidate resolveSingleByName(PluginIdentity identity, Resolver resolver) {
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
            candidates.sort(CANDIDATE_COMPARATOR.reversed());
            return candidates.get(0);
        }
        return null;
    }

    private static final List<String> SOURCE_TRUST_ORDER = List.of(
            "modrinth", "hangar", "direct", "github", "jenkins", "spigot", "spigot-premium", "ruspigot", "ruspigot-premium"
    );

    private static int paidRank(UpdateCandidate candidate) {
        return candidate.version() != null && UpdateSource.isPaidSource(candidate.version().sourceId()) ? 1 : 0;
    }

    private static boolean racesOnConfirmedNewerVersion(@Nullable UpdateCandidate candidate) {
        if (candidate == null) return false;
        if (candidate.identity().isPremium()) return false;
        UpdateStatus status = candidate.status();
        if (status == UpdateStatus.AMBIGUOUS_MATCH || status == UpdateStatus.FOUND_NOT_DOWNLOADABLE) {
            return false;
        }
        return status.hasNewerVersion() && candidate.installable();
    }

    private static final Comparator<RemoteVersion> VERSION_ORDER = (v1, v2) -> {
        String n1 = v1 == null ? null : v1.versionNumber();
        String n2 = v2 == null ? null : v2.versionNumber();
        if (n1 == null || n2 == null) return 0;
        return VersionUtil.compare(n1, n2);
    };

    static final Comparator<UpdateCandidate> CANDIDATE_COMPARATOR = Comparator
            .<UpdateCandidate>comparingInt(c -> racesOnConfirmedNewerVersion(c) ? 1 : 0)
            .thenComparingInt(c -> confidenceRank(c.confidence()))
            .thenComparing(UpdateCandidate::version, VERSION_ORDER)
            .thenComparingInt(UpdateService::paidRank)
            .thenComparingInt(c -> statusRank(c.status()))
            .thenComparingInt(c -> c.version() != null ? channelRank(c.version().channel()) : 0)
            .thenComparingInt(c -> c.version() != null ? trustRank(c.version().sourceId()) : 0);

    private static int statusRank(@Nullable UpdateStatus status) {
        if (status == null) return -1;
        return switch (status) {
            case UPDATE_AVAILABLE -> 100;
            case PRERELEASE_ONLY -> 80;
            case COMPAT_UNKNOWN -> 60;
            case UP_TO_DATE -> 50;
            case PENDING_RESTART -> 45;
            case AMBIGUOUS_MATCH -> 40;
            case FOUND_NOT_DOWNLOADABLE -> 35;
            case NO_SOURCE, RATE_LIMITED, NETWORK_ERROR -> 0;
        };
    }

    private static int channelRank(@Nullable ReleaseChannel channel) {
        if (channel == null) return 0;
        return switch (channel) {
            case RELEASE -> 30;
            case BETA -> 20;
            case ALPHA -> 10;
            case UNKNOWN -> 0;
        };
    }

    private static int trustRank(@Nullable String sourceId) {
        if (sourceId == null) return -1;
        int idx = SOURCE_TRUST_ORDER.indexOf(sourceId.toLowerCase(Locale.ROOT));
        return idx >= 0 ? (SOURCE_TRUST_ORDER.size() - idx) : 0;
    }

    private static int confidenceRank(@Nullable MatchConfidence confidence) {
        if (confidence == null) return 0;
        return switch (confidence) {
            case CONFIRMED -> 30;
            case LIKELY -> 20;
            case WEAK -> 10;
        };
    }

    private @Nullable UpdateCandidate resolveSingleFromCatalog(PluginIdentity identity, Resolver resolver) {
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
            candidates.sort(CANDIDATE_COMPARATOR.reversed());
            return candidates.get(0);
        }
        if (blockedByLimit) {
            return UpdateCandidate.failed(identity, UpdateStatus.RATE_LIMITED);
        }
        return null;
    }

    public ServerProfile getServerProfile() {
        return ServerProfile.detect();
    }

    public SourceCatalog getCatalog() {
        return catalog;
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

