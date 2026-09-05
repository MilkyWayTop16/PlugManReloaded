package ru.milkyway.plugmanreloaded.update;

public enum MatchConfidence {

    CONFIRMED,
    LIKELY,
    WEAK;

    public boolean allowsAutoInstall() {
        return this != WEAK;
    }
}

