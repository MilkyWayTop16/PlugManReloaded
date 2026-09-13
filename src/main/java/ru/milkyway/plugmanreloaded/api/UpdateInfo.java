package ru.milkyway.plugmanreloaded.api;

public record UpdateInfo(
        String pluginName,
        String currentVersion,
        String newVersion,
        String sourceId,
        String sourceUrl,
        boolean downloadable,
        boolean isPremium,
        boolean hasUpdate
) {
}
