package ru.milkyway.plugmanreloaded.update;

public enum UpdateStatus {

    UP_TO_DATE("update.up-to-date"),
    UPDATE_AVAILABLE("update.confirm"),
    FOUND_NOT_DOWNLOADABLE("update.not-downloadable"),
    COMPAT_UNKNOWN("update.confirm-compat-unknown"),
    PRERELEASE_ONLY("update.confirm-prerelease"),
    AMBIGUOUS_MATCH("update.confirm-ambiguous"),
    PENDING_RESTART("update.pending-restart"),
    NO_SOURCE("update.no-source"),
    RATE_LIMITED("update.rate-limited"),
    NETWORK_ERROR("update.network-error");

    private final String actionKey;

    UpdateStatus(String actionKey) {
        this.actionKey = actionKey;
    }

    public String actionKey() {
        return actionKey;
    }

    public boolean hasNewerVersion() {
        return this == UPDATE_AVAILABLE
                || this == FOUND_NOT_DOWNLOADABLE
                || this == COMPAT_UNKNOWN
                || this == PRERELEASE_ONLY
                || this == AMBIGUOUS_MATCH;
    }
}

