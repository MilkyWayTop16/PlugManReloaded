package ru.milkyway.plugmanreloaded.update;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.managers.PluginJarIndex;
import ru.milkyway.plugmanreloaded.utils.HashUtil;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class IdentityScanner {

    private final PlugManReloaded plugin;
    private final Map<String, String[]> hashCache = new ConcurrentHashMap<>();

    public IdentityScanner(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    public List<Plugin> snapshotLoadedPlugins() {
        if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null) {
            return Collections.emptyList();
        }
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        if (plugins == null || plugins.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.stream(plugins).filter(Objects::nonNull).toList();
    }

    public List<PluginIdentity> collectAll(List<Plugin> plugins) {
        List<PluginIdentity> identities = new ArrayList<>();
        Map<String, PluginIdentity> byName = new LinkedHashMap<>();
        if (plugins != null) {
            for (Plugin target : plugins) {
                PluginIdentity identity = collect(target);
                if (identity != null) {
                    byName.put(identity.pluginName().toLowerCase(Locale.ROOT), identity);
                }
            }
        }

        for (PluginJarIndex.JarInfo info : plugin.getPluginLifecycleManager().getJarIndex().getEntries()) {
            if (info.file() != null && info.file().isFile()) {
                String key = info.preferredName().toLowerCase(Locale.ROOT);
                if (!byName.containsKey(key)) {
                    PluginIdentity fileIdentity = collect(info.file());
                    if (fileIdentity != null) {
                        byName.put(key, fileIdentity);
                    }
                }
            }
        }

        identities.addAll(byName.values());
        return identities;
    }

    public @Nullable PluginIdentity collect(@Nullable Plugin target) {
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
        PluginEdition edition = EditionDetector.detect(
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

    public @Nullable PluginIdentity collect(@Nullable File jar) {
        if (jar == null || !jar.isFile()) return null;

        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry entry = jarFile.getJarEntry("plugin.yml");
            if (entry == null) entry = jarFile.getJarEntry("paper-plugin.yml");
            if (entry == null) return null;

            try (Reader reader = new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
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
                PluginEdition edition = EditionDetector.detect(
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
                PluginJarIndex.JarDescriptor desc =
                        PluginJarIndex.readDescriptor(directPending);
                if (desc != null && desc.version() != null && !desc.version().isBlank()) {
                    return desc.version();
                }
            }

            File[] files = updateFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (files != null) {
                for (File f : files) {
                    PluginJarIndex.JarDescriptor desc =
                            PluginJarIndex.readDescriptor(f);
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

            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            try (InputStream raw = Files.newInputStream(jar.toPath());
                 DigestInputStream first = new DigestInputStream(raw, sha1);
                 DigestInputStream second = new DigestInputStream(first, sha256)) {
                byte[] buffer = new byte[16384];
                while (second.read(buffer) != -1) {
                    continue;
                }
            }

            String[] result = new String[]{HashUtil.toHex(sha1.digest()), HashUtil.toHex(sha256.digest())};
            hashCache.put(cacheKey, result);
            return result;
        } catch (Throwable t) {
            Log.debug("identityscanner.hash-failed", t, "file", jar.getName());
            return new String[]{null, null};
        }
    }
}

