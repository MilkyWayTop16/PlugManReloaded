package ru.milkyway.plugmanreloaded.configs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigUpdater {

    private final PlugManReloaded plugin;

    public ConfigUpdater(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    public void update(File targetFile, String resourcePath) {
        if (!targetFile.exists()) return;

        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(targetFile);
        FileConfiguration defaultConfig = getDefaultConfig(plugin, resourcePath);

        if (defaultConfig == null) {
            Log.warn("configupdater.default-load-failed", "resource", resourcePath);
            return;
        }

        String defaultVersion = defaultConfig.getString("config-version", "1.0");
        String userVersion = userConfig.getString("config-version", "1.0");
        boolean versionBump = compareVersions(userVersion, defaultVersion) < 0;

        try {
            if (userConfig.options().getHeader() == null || userConfig.options().getHeader().isEmpty()) {
                userConfig.options().setHeader(defaultConfig.options().getHeader());
            }
            if (userConfig.options().getFooter() == null || userConfig.options().getFooter().isEmpty()) {
                userConfig.options().setFooter(defaultConfig.options().getFooter());
            }
        } catch (Throwable t) {
            Log.debug("configupdater.header-footer-unsupported", t);
        }

        boolean mergedSomething = mergeMissingKeys(userConfig, defaultConfig, "");
        if (versionBump) {
            userConfig.set("config-version", defaultVersion);
        }

        if (!mergedSomething && !versionBump) {
            return;
        }

        createBackup(plugin, targetFile);
        String successKey = mergedSomething ? "configupdater.updated" : "configupdater.version-bumped";
        saveConfig(targetFile, userConfig, successKey, "file", targetFile.getName(), "version", defaultVersion);
    }

    private int compareVersions(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private int[] parseVersion(@Nullable String v) {
        if (v == null || v.isBlank()) return new int[]{1, 0};
        String[] parts = v.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                Log.debug("configupdater.version-fragment-nonnumeric", "fragment", parts[i]);
                out[i] = 0;
            }
        }
        return out;
    }

    private static final boolean SUPPORTS_COMMENTS;
    static {
        boolean supports = false;
        try {
            ConfigurationSection.class.getMethod("getComments", String.class);
            supports = true;
        } catch (Throwable ignored) {}
        SUPPORTS_COMMENTS = supports;
    }

    private boolean mergeMissingKeys(ConfigurationSection target, ConfigurationSection source, String path) {
        boolean changed = false;
        for (String key : source.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            List<String> comments = null;
            List<String> inlineComments = null;

            if (SUPPORTS_COMMENTS) {
                try {
                    comments = source.getComments(key);
                    inlineComments = source.getInlineComments(key);
                } catch (Throwable ignored) {}
            }

            if (!target.contains(key)) {
                target.set(key, source.get(key));
                if (SUPPORTS_COMMENTS) {
                    try {
                        if (comments != null && !comments.isEmpty()) {
                            target.setComments(key, comments);
                        }
                        if (inlineComments != null && !inlineComments.isEmpty()) {
                            target.setInlineComments(key, inlineComments);
                        }
                    } catch (Throwable ignored) {}
                }
                changed = true;
            } else {
                if (SUPPORTS_COMMENTS) {
                    try {
                        if (target.getComments(key) == null || target.getComments(key).isEmpty()) {
                            if (comments != null && !comments.isEmpty()) {
                                target.setComments(key, comments);
                            }
                        }
                        if (target.getInlineComments(key) == null || target.getInlineComments(key).isEmpty()) {
                            if (inlineComments != null && !inlineComments.isEmpty()) {
                                target.setInlineComments(key, inlineComments);
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                if (source.isConfigurationSection(key) && target.isConfigurationSection(key)) {
                    ConfigurationSection targetSection = target.getConfigurationSection(key);
                    ConfigurationSection sourceSection = source.getConfigurationSection(key);
                    if (targetSection != null && sourceSection != null) {
                        if (mergeMissingKeys(targetSection, sourceSection, fullPath)) {
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    static void createBackup(PlugManReloaded plugin, File file) {
        try {
            File backupDir = new File(file.getParentFile(), "backups/configs");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            File backup = new File(backupDir, file.getName() + "." + time + ".bak");
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.info("configupdater.backup-created", "file", backup.getName());
            rotateBackups(backupDir, file.getName(), 3);
        } catch (Exception e) {
            Log.warn("configupdater.backup-failed", e, "file", file.getName());
        }
    }

    private static void rotateBackups(File backupDir, String configName, int maxBackups) {
        File[] files = backupDir.listFiles((dir, name) -> name.startsWith(configName) && name.endsWith(".bak"));
        if (files == null || files.length <= maxBackups) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int toDelete = files.length - maxBackups;
        for (int i = 0; i < toDelete; i++) {
            try {
                Files.deleteIfExists(files[i].toPath());
            } catch (Exception e) {
                Log.debug("configupdater.old-backup-delete-failed", e, "file", files[i].getName());
            }
        }
    }

    private void saveConfig(File file, FileConfiguration config, String successKey, String... successPh) {
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            config.save(tempFile);
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception fallback) {
                Log.debug("configupdater.atomic-move-unsupported", fallback);
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Log.info(successKey, successPh);
        } catch (Exception e) {
            Log.warn("configupdater.save-failed", e, "file", file.getName());
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (Exception cleanup) {
                Log.debug("configupdater.temp-file-delete-failed", cleanup, "file", tempFile.getName());
            }
        }
    }

    private static final Map<String, FileConfiguration> DEFAULT_CONFIG_CACHE = new ConcurrentHashMap<>();

    public static @Nullable FileConfiguration getDefaultConfig(PlugManReloaded plugin, @Nullable String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) return null;
        return DEFAULT_CONFIG_CACHE.computeIfAbsent(resourcePath, path -> loadDefaultConfigInternal(plugin, path));
    }

    private static @Nullable FileConfiguration loadDefaultConfigInternal(PlugManReloaded plugin, String resourcePath) {
        InputStream stream = plugin != null ? plugin.getResource(resourcePath) : null;
        if (stream == null) {
            stream = ConfigUpdater.class.getClassLoader().getResourceAsStream(resourcePath);
        }
        if (stream == null) return null;

        try (InputStream in = stream) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.warn("configupdater.default-read-error", e, "resource", resourcePath);
            return null;
        }
    }
}

