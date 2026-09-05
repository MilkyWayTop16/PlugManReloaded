package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum ReleaseChannel {

    RELEASE,
    BETA,
    ALPHA,
    UNKNOWN;

    public boolean isPrerelease() {
        return this == BETA || this == ALPHA;
    }

    public static ReleaseChannel parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return RELEASE;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains("alpha") || value.contains("snapshot") || value.contains("dev")) return ALPHA;
        if (value.contains("beta") || value.contains("rc") || value.contains("pre")) return BETA;
        return RELEASE;
    }
}

