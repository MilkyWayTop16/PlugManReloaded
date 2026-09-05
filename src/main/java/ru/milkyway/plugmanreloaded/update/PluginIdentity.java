package ru.milkyway.plugmanreloaded.update;

import java.io.File;
import java.util.List;

public record PluginIdentity(
        String pluginName,
        String mainClass,
        String currentVersion,
        List<String> authors,
        String website,
        String sha1,
        String sha256,
        File jarFile,
        String pendingVersion,
        PluginEdition edition
) {

    public PluginIdentity(String pluginName, String mainClass, String currentVersion, List<String> authors, String website, String sha1, String sha256, File jarFile) {
        this(pluginName, mainClass, currentVersion, authors, website, sha1, sha256, jarFile, null, PluginEdition.FREE);
    }

    public PluginIdentity(String pluginName, String mainClass, String currentVersion, List<String> authors, String website, String sha1, String sha256, File jarFile, String pendingVersion) {
        this(pluginName, mainClass, currentVersion, authors, website, sha1, sha256, jarFile, pendingVersion, PluginEdition.FREE);
    }

    public boolean isPremium() {
        return edition != null && edition.isPremium();
    }

    public boolean hashesAvailable() {
        return sha1 != null && !sha1.isBlank();
    }
}

