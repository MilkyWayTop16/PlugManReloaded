package ru.milkyway.plugmanreloaded.update;

public enum PluginEdition {
    FREE,
    PREMIUM,
    UNKNOWN;

    public boolean isPremium() {
        return this == PREMIUM;
    }
}

