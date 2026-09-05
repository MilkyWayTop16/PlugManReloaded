package ru.milkyway.plugmanreloaded.utils;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GameVersionFormatter {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?(.*)$", Pattern.CASE_INSENSITIVE);

    private GameVersionFormatter() {}

    public static String formatRanges(Collection<String> versions) {
        return formatRanges(versions, "Any");
    }

    public static String formatRanges(@Nullable Collection<String> versions, String defaultIfEmpty) {
        if (versions == null || versions.isEmpty()) {
            return defaultIfEmpty != null ? defaultIfEmpty : "Any";
        }

        Set<String> unique = new LinkedHashSet<>();
        for (String v : versions) {
            if (v != null && !v.isBlank()) {
                unique.add(v.trim());
            }
        }

        if (unique.isEmpty()) {
            return defaultIfEmpty != null ? defaultIfEmpty : "Any";
        }

        List<ParsedVersion> parsed = new ArrayList<>();
        List<String> unparseable = new ArrayList<>();

        for (String raw : unique) {
            ParsedVersion pv = parse(raw);
            if (pv != null) {
                parsed.add(pv);
            } else {
                unparseable.add(raw);
            }
        }

        parsed.sort(Comparator.naturalOrder());

        List<ParsedVersion> deduplicated = new ArrayList<>();
        for (ParsedVersion pv : parsed) {
            if (deduplicated.isEmpty() || !deduplicated.get(deduplicated.size() - 1).isSameVersion(pv)) {
                deduplicated.add(pv);
            }
        }

        List<String> ranges = new ArrayList<>();
        int i = 0;
        while (i < deduplicated.size()) {
            ParsedVersion start = deduplicated.get(i);
            ParsedVersion prev = start;
            int j = i + 1;

            while (j < deduplicated.size()) {
                ParsedVersion next = deduplicated.get(j);
                if (isContiguous(prev, next)) {
                    prev = next;
                    j++;
                } else {
                    break;
                }
            }

            ParsedVersion end = prev;
            if (start.equals(end)) {
                ranges.add(start.raw);
            } else {
                ranges.add(start.raw + " – " + end.raw);
            }

            i = j;
        }

        ranges.addAll(unparseable);

        if (ranges.isEmpty()) {
            return defaultIfEmpty != null ? defaultIfEmpty : "Any";
        }

        return String.join(", ", ranges);
    }

    private static boolean isContiguous(ParsedVersion a, ParsedVersion b) {
        if (a.major == b.major) {
            if (a.minor == b.minor) {
                return (b.patch - a.patch) <= 1 || (a.patch == 0 && b.patch <= 2);
            }
            if (b.minor - a.minor == 1) {
                return true;
            }
            return false;
        }

        if (a.major == 1 && a.minor >= 20 && b.major == 26) {
            return true;
        }

        if (b.major - a.major == 1 && b.minor <= 1) {
            return true;
        }

        return false;
    }

    private static @Nullable ParsedVersion parse(String raw) {
        String clean = raw.trim().replaceFirst("^[vV]+", "");
        Matcher matcher = VERSION_PATTERN.matcher(clean);
        if (!matcher.matches()) {
            return null;
        }

        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
            int build = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 0;
            String suffix = matcher.group(5) != null ? matcher.group(5).trim() : "";

            return new ParsedVersion(clean, major, minor, patch, build, suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class ParsedVersion implements Comparable<ParsedVersion> {
        private final String raw;
        private final int major;
        private final int minor;
        private final int patch;
        private final int build;
        private final String suffix;

        private ParsedVersion(String raw, int major, int minor, int patch, int build, String suffix) {
            this.raw = raw;
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.build = build;
            this.suffix = suffix != null ? suffix : "";
        }

        public boolean isSameVersion(ParsedVersion o) {
            return this.major == o.major
                    && this.minor == o.minor
                    && this.patch == o.patch
                    && this.build == o.build
                    && this.suffix.equalsIgnoreCase(o.suffix);
        }

        @Override
        public int compareTo(ParsedVersion o) {
            if (this.major != o.major) return Integer.compare(this.major, o.major);
            if (this.minor != o.minor) return Integer.compare(this.minor, o.minor);
            if (this.patch != o.patch) return Integer.compare(this.patch, o.patch);
            if (this.build != o.build) return Integer.compare(this.build, o.build);
            return this.suffix.compareToIgnoreCase(o.suffix);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ParsedVersion other)) return false;
            return this.raw.equals(other.raw);
        }

        @Override
        public int hashCode() {
            return this.raw.hashCode();
        }
    }
}

