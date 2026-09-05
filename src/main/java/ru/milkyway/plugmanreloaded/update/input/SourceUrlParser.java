package ru.milkyway.plugmanreloaded.update.input;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceUrlParser {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "github.com",
            "www.github.com",
            "modrinth.com",
            "www.modrinth.com",
            "hangar.papermc.io",
            "spigotmc.org",
            "www.spigotmc.org",
            "spigotmc.ru",
            "www.spigotmc.ru"
    );

    private static final Pattern SPIGOT_RESOURCE_PATTERN = Pattern.compile("(?:/|^)resources/(?:[^/]*?[.])?(\\d+)(?:/|$|\\?|#)");

    private static @Nullable String extractSpigotResourceId(@Nullable String path) {
        if (path == null || path.isBlank()) return null;
        Matcher matcher = SPIGOT_RESOURCE_PATTERN.matcher(path);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher fallback = Pattern.compile("(?:\\.|/|^)(\\d{1,8})(?:/|$|\\?|#)").matcher(path);
        String last = null;
        while (fallback.find()) {
            last = fallback.group(1);
        }
        return last;
    }

    public record ParseResult(boolean success, SourceCatalog.CatalogSource source, String notice, String errorReason) {
        public static ParseResult ofSuccess(SourceCatalog.CatalogSource source, String notice) {
            return new ParseResult(true, source, notice, null);
        }

        public static ParseResult ofError(String errorReason) {
            return new ParseResult(false, null, null, errorReason);
        }
    }

    private static final String ERRORS = "actions.update.manual-source.errors.";
    private static final String NOTICES = "actions.update.manual-source.notices.";

    private SourceUrlParser() {
    }

    public static ParseResult parse(@Nullable String rawInput) {
        String input = normalize(rawInput);
        if (input.isBlank()) {
            return ParseResult.ofError(ERRORS + "empty-link");
        }

        URI uri;
        try {
            uri = URI.create(input);
        } catch (Exception e) {
            return ParseResult.ofError(ERRORS + "bad-url");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ParseResult.ofError(ERRORS + "no-host");
        }
        host = host.toLowerCase(Locale.ROOT);

        String path = uri.getPath() != null ? uri.getPath() : "";
        if (path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return ParseResult.ofError(ERRORS + "direct-jar");
        }

        boolean isJenkins = path.contains("/job/");
        if (!ALLOWED_HOSTS.contains(host) && !isJenkins) {
            return ParseResult.ofError(ERRORS + "host-not-allowed");
        }

        String[] parts = splitPath(path);
        return switch (host) {
            case "github.com", "www.github.com" -> parseGithub(parts);
            case "modrinth.com", "www.modrinth.com" -> parseModrinth(parts);
            case "hangar.papermc.io" -> parseHangar(parts);
            case "spigotmc.org", "www.spigotmc.org" -> parseSpigot(path);
            case "spigotmc.ru", "www.spigotmc.ru" -> parseRuspigot(parts);
            default -> isJenkins
                    ? parseJenkins(uri, host, path)
                    : ParseResult.ofError(ERRORS + "unknown-source");
        };
    }

    private static String normalize(@Nullable String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        String input = rawInput.trim()
                .replaceAll("^[<\"'(`\\[]+", "")
                .replaceAll("[>\"')`\\]]+$", "")
                .trim();
        if (input.isBlank()) {
            return "";
        }
        return input.startsWith("http://") || input.startsWith("https://") ? input : "https://" + input;
    }

    private static String[] splitPath(String path) {
        String normalized = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized.split("/+");
    }

    private static ParseResult parseGithub(String[] parts) {
        if (parts.length < 1 || parts[0].isBlank()) {
            return ParseResult.ofError(ERRORS + "github-format");
        }
        String owner = parts[0];

        if (parts.length < 2 || parts[1].isBlank()) {
            return ParseResult.ofSuccess(new SourceCatalog.CatalogSource(
                    "github", owner, "https://github.com/" + owner, Map.of("ownerOnly", "true")), null);
        }

        String ref = owner + "/" + parts[1];
        return ParseResult.ofSuccess(new SourceCatalog.CatalogSource(
                "github", ref, "https://github.com/" + ref, Map.of()), null);
    }

    private static ParseResult parseModrinth(String[] parts) {
        if (parts.length >= 1 && isAny(parts[0], "user", "organization", "members")) {
            return ParseResult.ofError(ERRORS + "modrinth-profile");
        }

        String slug = null;
        if (parts.length >= 2 && isAny(parts[0], "plugin", "mod", "project", "datapack", "resourcepack", "shader", "modpack")) {
            slug = parts[1];
        } else if (parts.length >= 1 && !parts[0].isBlank()) {
            slug = parts[0];
        }
        if (slug == null || slug.isBlank()) {
            return ParseResult.ofError(ERRORS + "modrinth-format");
        }
        return ParseResult.ofSuccess(new SourceCatalog.CatalogSource(
                "modrinth", slug, "https://modrinth.com/plugin/" + slug, Map.of()), null);
    }

    private static ParseResult parseHangar(String[] parts) {
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return ParseResult.ofError(ERRORS + "hangar-format");
        }
        String ref = parts[0] + "/" + parts[1];
        return ParseResult.ofSuccess(new SourceCatalog.CatalogSource(
                "hangar", ref, "https://hangar.papermc.io/" + ref, Map.of()), null);
    }

    private static ParseResult parseSpigot(String path) {
        String resourceId = extractSpigotResourceId(path);
        if (resourceId == null || resourceId.isBlank()) {
            return ParseResult.ofError(ERRORS + "spigot-format");
        }
        return ParseResult.ofSuccess(
                new SourceCatalog.CatalogSource("spigot", resourceId,
                        "https://www.spigotmc.org/resources/" + resourceId, Map.of()),
                NOTICES + "spigot");
    }

    private static ParseResult parseRuspigot(String[] parts) {
        if (parts.length < 1 || parts[0].isBlank() || !"resources".equalsIgnoreCase(parts[0])) {
            return ParseResult.ofError(ERRORS + "ruspigot-format");
        }
        String pageUrl = "https://spigotmc.ru/resources/" + (parts.length > 1 ? parts[1] : "");
        return ParseResult.ofSuccess(
                new SourceCatalog.CatalogSource("ruspigot", pageUrl, pageUrl, Map.of()),
                NOTICES + "ruspigot");
    }

    private static ParseResult parseJenkins(URI uri, String host, String path) {
        int lastJob = path.lastIndexOf("/job/");
        if (lastJob < 0) {
            return ParseResult.ofError(ERRORS + "jenkins-format");
        }

        int nextSlash = path.indexOf('/', lastJob + 5);
        String jobPath = nextSlash > 0 ? path.substring(0, nextSlash) : path;
        String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
        int port = uri.getPort();
        String hostPort = (port > 0 && port != 80 && port != 443) ? host + ":" + port : host;

        String endpoint = (scheme + "://" + hostPort + jobPath).replaceAll("/+$", "");
        return ParseResult.ofSuccess(new SourceCatalog.CatalogSource(
                "jenkins", endpoint, endpoint, Map.of("endpoint", endpoint)), null);
    }

    private static boolean isAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
