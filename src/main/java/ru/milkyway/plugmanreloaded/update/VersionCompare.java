package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VersionCompare {

    private VersionCompare() {}

    private static final List<String> PRE_QUALIFIERS = List.of("snapshot", "dev", "alpha", "beta", "rc", "pre");
    private static final Set<String> RELEASE_QUALIFIERS = Set.of("release", "final", "ga");

    private static final Set<String> PLATFORM_TOKENS = Set.of(
            "bukkit", "spigot", "paper", "purpur", "folia", "velocity",
            "bungee", "bungeecord", "waterfall", "sponge", "fabric", "forge", "neoforge"
    );

    public static int compare(String left, String right) {
        List<String> a = split(left);
        List<String> b = split(right);
        int max = Math.max(a.size(), b.size());

        for (int i = 0; i < max; i++) {
            String partA = i < a.size() ? a.get(i) : "";
            String partB = i < b.size() ? b.get(i) : "";

            Integer numA = asNumber(partA);
            Integer numB = asNumber(partB);

            if (partA.isEmpty() && numB != null) {
                numA = 0;
            }
            if (partB.isEmpty() && numA != null) {
                numB = 0;
            }

            if (numA != null && numB != null) {
                int cmp = Integer.compare(numA, numB);
                if (cmp != 0) return cmp;
                continue;
            }

            if (numA != null) return 1;
            if (numB != null) return -1;

            int cmp = compareQualifiers(partA, partB);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    public static boolean parseable(String version) {
        for (String part : split(version)) {
            if (asNumber(part) != null) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(@Nullable String version) {
        List<String> parts = new ArrayList<>();
        if (version == null || version.isBlank()) {
            return parts;
        }

        String normalized = version.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }

        StringBuilder current = new StringBuilder();
        boolean lastWasDigit = false;

        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == '+' || c == ' ') {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                lastWasDigit = false;
                continue;
            }

            boolean isDigit = Character.isDigit(c);
            if (current.length() > 0 && isDigit != lastWasDigit) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
            lastWasDigit = isDigit;
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        while (!parts.isEmpty() && PLATFORM_TOKENS.contains(parts.get(parts.size() - 1))) {
            parts.remove(parts.size() - 1);
        }
        return parts;
    }

    private static @Nullable Integer asNumber(@Nullable String part) {
        if (part == null || part.isEmpty()) return null;
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compareQualifiers(String left, String right) {
        if (left.equals(right)) return 0;

        boolean leftIsRelease = left.isEmpty() || RELEASE_QUALIFIERS.contains(left);
        boolean rightIsRelease = right.isEmpty() || RELEASE_QUALIFIERS.contains(right);

        if (leftIsRelease && rightIsRelease) {
            return 0;
        }

        int indexLeft = PRE_QUALIFIERS.indexOf(left);
        int indexRight = PRE_QUALIFIERS.indexOf(right);

        if (leftIsRelease && indexRight >= 0) {
            return 1;
        }
        if (rightIsRelease && indexLeft >= 0) {
            return -1;
        }

        if (indexLeft >= 0 && indexRight >= 0) {
            return Integer.compare(indexLeft, indexRight);
        }
        if (indexLeft >= 0) return -1;
        if (indexRight >= 0) return 1;
        return left.compareTo(right);
    }
}

