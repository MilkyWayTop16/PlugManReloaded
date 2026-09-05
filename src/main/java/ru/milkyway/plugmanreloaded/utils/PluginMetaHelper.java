package ru.milkyway.plugmanreloaded.utils;

import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
}

