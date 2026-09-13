package ru.milkyway.plugmanreloaded.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.api.PluginInfo;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public final class PluginMetaHelper {

    private PluginMetaHelper() {}

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public static String cleanVersion(@Nullable String version) {
        if (version == null || version.isBlank()) {
            return "1.0";
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            Log.warn("pluginmetahelper.version-not-substituted", "placeholder", trimmed);
            return "unknown";
        }
        String stripped = trimmed.replaceFirst("^[vV]+", "");
        return stripped.isEmpty() ? trimmed : stripped;
    }

    public static boolean isVersionKey(@Nullable String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("version") || lower.equals("ver") || lower.equals("current")
                || lower.equals("latest") || lower.equals("from") || lower.equals("to")
                || lower.equals("old-version") || lower.equals("new-version") || lower.equals("old_version")
                || lower.equals("new_version") || lower.endsWith("-version") || lower.endsWith("_version");
    }

    public static String cleanupDoubleV(@Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("(?i)(?<=^|[^a-zA-Z0-9_#&§])[vV]+([vV])(?=\\d)", "$1")
                   .replaceAll("(?i)(&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]|§[0-9a-fk-orA-FK-OR])+[vV]+([vV])(?=\\d)", "$1v");
    }

    public static String getVersion(@Nullable Plugin plugin) {
        if (plugin == null) return "1.0";

        try {
            Object meta = ReflectionHelper.invokeMethod(plugin, "getPluginMeta");
            if (meta != null) {
                String version = ReflectionHelper.invokeMethod(meta, "getVersion");
                if (version != null && !version.isBlank()) {
                    return cleanVersion(version);
                }
            }
        } catch (Throwable t) {
            Log.debug("pluginmetahelper.version-pluginmeta-failed", t, "plugin", plugin.getName());
        }

        try {
            PluginDescriptionFile desc = plugin.getDescription();
            if (desc != null && !desc.getVersion().isBlank()) {
                return cleanVersion(desc.getVersion());
            }
        } catch (Throwable t) {
            Log.debug("pluginmetahelper.version-description-failed", t, "plugin", plugin.getName());
        }
        return "1.0";
    }

    public static List<String> getAuthors(@Nullable Plugin plugin) {
        if (plugin == null) return Collections.emptyList();

        try {
            Object meta = ReflectionHelper.invokeMethod(plugin, "getPluginMeta");
            if (meta != null) {
                List<String> authors = ReflectionHelper.invokeMethod(meta, "getAuthors");
                if (authors != null && !authors.isEmpty()) {
                    return authors;
                }
            }
        } catch (Throwable t) {
            Log.debug("pluginmetahelper.authors-pluginmeta-failed", t, "plugin", plugin.getName());
        }

        try {
            PluginDescriptionFile desc = plugin.getDescription();
            if (desc != null) {
                List<String> authors = desc.getAuthors();
                if (authors != null && !authors.isEmpty()) {
                    return authors;
                }
            }
        } catch (Throwable t) {
            Log.debug("pluginmetahelper.authors-description-failed", t, "plugin", plugin.getName());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static List<String> getPermissionNames(@Nullable Plugin plugin) {
        if (plugin == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();

        try {
            PluginDescriptionFile desc = plugin.getDescription();
            if (desc != null) {
                for (Permission perm : desc.getPermissions()) {
                    if (perm != null && perm.getName() != null && !perm.getName().isBlank()) {
                        names.add(perm.getName());
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("pluginmetahelper.permissions-description-failed", t, "plugin", plugin.getName());
        }

        try {
            Object meta = ReflectionHelper.invokeMethod(plugin, "getPluginMeta");
            if (meta != null) {
                Collection<Permission> metaPerms = ReflectionHelper.invokeMethod(meta, "getPermissions");
                if (metaPerms != null) {
                    for (Permission perm : metaPerms) {
                        if (perm != null && perm.getName() != null && !perm.getName().isBlank()) {
                            if (!names.contains(perm.getName())) {
                                names.add(perm.getName());
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("pluginmetahelper.permissions-pluginmeta-failed", t, "plugin", plugin.getName());
        }

        return names;
    }

    public static @Nullable PluginInfo fromPlugin(@Nullable Plugin plugin, File file, boolean isPaper) {
        if (plugin == null) return null;
        PluginDescriptionFile desc = plugin.getDescription();

        long size = (file != null && file.exists()) ? file.length() : 0;
        Set<String> perms = desc.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());

        boolean hasBoot = file != null && JarValidator.hasPaperBootstrapper(file);

        return new PluginInfo(
                plugin.getName(),
                desc.getVersion(),
                desc.getMain(),
                desc.getAuthors(),
                desc.getDescription() != null ? desc.getDescription() : "",
                desc.getWebsite() != null ? desc.getWebsite() : "",
                desc.getDepend(),
                desc.getSoftDepend(),
                desc.getCommands(),
                perms,
                file,
                size,
                plugin.isEnabled(),
                isPaper,
                hasBoot
        );
    }

    public static @Nullable PluginInfo fromJarFile(@Nullable File file) {
        if (file == null || !file.exists()) return null;

        Descriptor descriptor = readDescriptor(file);
        return new PluginInfo(
                descriptor.name(),
                descriptor.version(),
                descriptor.main(),
                descriptor.authors(),
                descriptor.description(),
                descriptor.website(),
                descriptor.depends(),
                descriptor.softDepends(),
                Collections.emptyMap(),
                Collections.emptySet(),
                file,
                file.length(),
                false,
                descriptor.paperPlugin(),
                descriptor.hasBootstrapper()
        );
    }

    private record Descriptor(String name, String version, String main, List<String> authors,
                              String description, String website, List<String> depends,
                              List<String> softDepends, boolean paperPlugin, boolean hasBootstrapper) {

        static Descriptor unknown(File file, boolean paperPlugin) {
            return new Descriptor(file.getName(), "1.0", "", Collections.emptyList(),
                    "", "", Collections.emptyList(), Collections.emptyList(), paperPlugin, false);
        }
    }

    private static Descriptor readDescriptor(File file) {
        boolean isPaper = false;
        try (JarFile jar = new JarFile(file)) {
            JarEntry paperEntry = jar.getJarEntry("paper-plugin.yml");
            isPaper = paperEntry != null;

            JarEntry entry = isPaper ? paperEntry : jar.getJarEntry("plugin.yml");
            if (entry == null) {
                return Descriptor.unknown(file, false);
            }

            try (InputStream is = jar.getInputStream(entry)) {
                return readYaml(JarValidator.loadSafeYaml(is), file, isPaper);
            }
        } catch (Exception | LinkageError t) {
            Log.debug("plugininfo.plugin-yml-read-failed", t);
            return Descriptor.unknown(file, isPaper);
        }
    }

    private static Descriptor readYaml(YamlConfiguration yaml, File file, boolean isPaper) {
        Descriptor fallback = Descriptor.unknown(file, isPaper);
        return new Descriptor(
                yaml.getString("name", fallback.name()),
                yaml.getString("version", fallback.version()),
                yaml.getString("main", fallback.main()),
                readAuthors(yaml),
                yaml.getString("description", fallback.description()),
                yaml.getString("website", fallback.website()),
                yaml.isList("depend") ? yaml.getStringList("depend") : Collections.emptyList(),
                yaml.isList("softdepend") ? yaml.getStringList("softdepend") : Collections.emptyList(),
                isPaper,
                isPaper && hasBootstrapperEntry(yaml)
        );
    }

    private static List<String> readAuthors(YamlConfiguration yaml) {
        if (yaml.isList("authors")) {
            return yaml.getStringList("authors");
        }
        String single = yaml.getString("author");
        return single != null ? List.of(single) : Collections.emptyList();
    }

    private static boolean hasBootstrapperEntry(YamlConfiguration yaml) {
        String bootstrapper = yaml.getString("bootstrapper");
        String loader = yaml.getString("loader");
        return (bootstrapper != null && !bootstrapper.isBlank())
                || (loader != null && !loader.isBlank());
    }
}

