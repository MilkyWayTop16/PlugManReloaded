package ru.milkyway.plugmanreloaded.api;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record PluginInfo(
        String name,
        String version,
        String mainClass,
        List<String> authors,
        String description,
        String website,
        List<String> depends,
        List<String> softDepends,
        Map<String, Map<String, Object>> commands,
        Set<String> permissions,
        File file,
        long fileSizeBytes,
        boolean enabled,
        boolean paperPlugin,
        boolean hasBootstrapper
) {

    public String fileSizeFormatted() {
        if (fileSizeBytes <= 0) return "0 KB";
        if (fileSizeBytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", fileSizeBytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", fileSizeBytes / (1024.0 * 1024.0));
    }
}

