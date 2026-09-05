package ru.milkyway.plugmanreloaded.update;

import java.time.Instant;
import java.util.Set;

public record RemoteVersion(
        String sourceId,
        String projectRef,
        String projectUrl,
        String versionNumber,
        ReleaseChannel channel,
        Set<String> gameVersions,
        Set<String> loaders,
        String downloadUrl,
        String fileName,
        String expectedSha1,
        String expectedSha256,
        long sizeBytes,
        Instant published
) {

    public boolean downloadable() {
        return downloadUrl != null && !downloadUrl.isBlank();
    }

    public boolean compatibilityKnown() {
        return gameVersions != null && !gameVersions.isEmpty();
    }

    public boolean supportsGameVersion(String serverVersion) {
        if (!compatibilityKnown() || serverVersion == null || serverVersion.isBlank()) {
            return false;
        }
        String server = serverVersion.trim();
        for (String gv : gameVersions) {
            if (gv == null || gv.isBlank()) continue;
            String clean = gv.trim();
            if (clean.equalsIgnoreCase("all") || clean.equals("*")) {
                return true;
            }
            if (clean.equalsIgnoreCase(server)) {
                return true;
            }
            if (clean.endsWith(".x") || clean.endsWith(".X") || clean.endsWith(".*")) {
                String prefix = clean.substring(0, clean.length() - 2);
                if (server.equals(prefix) || server.startsWith(prefix + ".")) {
                    return true;
                }
            }
            if (clean.matches("^\\d+\\.\\d+$")) {
                if (server.equals(clean) || server.startsWith(clean + ".")) {
                    return true;
                }
            }
            int dash = clean.indexOf('-');
            if (dash > 0) {
                String min = clean.substring(0, dash).trim();
                String max = clean.substring(dash + 1).trim();
                if (VersionCompare.compare(server, min) >= 0 && VersionCompare.compare(server, max) <= 0) {
                    return true;
                }
            }
        }
        return false;
    }
}

