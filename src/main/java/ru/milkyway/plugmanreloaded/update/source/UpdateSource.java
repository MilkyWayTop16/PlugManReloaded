package ru.milkyway.plugmanreloaded.update.source;

import ru.milkyway.plugmanreloaded.update.MatchConfidence;
import ru.milkyway.plugmanreloaded.update.MatchReason;
import ru.milkyway.plugmanreloaded.update.PluginIdentity;
import ru.milkyway.plugmanreloaded.update.RemoteVersion;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface UpdateSource {

    String id();

    boolean supportsAutoInstall();

    static boolean isPaidSource(String sourceId) {
        return "spigot-premium".equals(sourceId) || "ruspigot-premium".equals(sourceId);
    }

    static String displayName(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return "Source";
        return switch (sourceId.toLowerCase(Locale.ROOT)) {
            case "modrinth" -> "Modrinth";
            case "hangar" -> "Hangar";
            case "spigot", "spigotmc" -> "SpigotMC";
            case "github" -> "GitHub";
            case "jenkins" -> "Jenkins";
            case "ruspigot" -> "RuSpigot";
            default -> sourceId;
        };
    }

    Map<String, ProjectMatch> identifyBatch(List<PluginIdentity> identities);

    default ProjectMatch identifyFromCatalog(PluginIdentity identity, String ref, Map<String, String> options) {
        return null;
    }

    default ProjectMatch identifyByName(PluginIdentity identity) {
        return null;
    }

    List<RemoteVersion> listVersions(ProjectMatch match);

    default String projectTitle(String ref) {
        return ref;
    }

    record ProjectMatch(
            String pluginName,
            String projectRef,
            String projectUrl,
            MatchConfidence confidence,
            MatchReason reason,
            String knownVersionNumber
    ) {
        public ProjectMatch(String projectRef, String projectUrl, MatchConfidence confidence, MatchReason reason, String knownVersionNumber) {
            this(null, projectRef, projectUrl, confidence, reason, knownVersionNumber);
        }
    }
}

