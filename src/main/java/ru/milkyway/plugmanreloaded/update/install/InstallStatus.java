package ru.milkyway.plugmanreloaded.update.install;

public enum InstallStatus {

    INSTALLED("update.installed"),
    DOWNLOAD_FAILED("update.download-failed"),
    HASH_MISMATCH("update.hash-mismatch"),
    WRONG_PLUGIN("update.wrong-plugin"),
    MISSING_DEPENDENCY("update.missing-dependency"),
    PENDING_RESTART("update.pending-restart"),
    ROLLED_BACK("update.rolled-back"),
    NOT_INSTALLABLE("update.not-installable");

    private final String actionKey;

    InstallStatus(String actionKey) {
        this.actionKey = actionKey;
    }

    public String actionKey() {
        return actionKey;
    }

    public boolean success() {
        return this == INSTALLED || this == PENDING_RESTART;
    }
}

