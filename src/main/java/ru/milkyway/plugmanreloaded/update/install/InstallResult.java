package ru.milkyway.plugmanreloaded.update.install;

public record InstallResult(InstallStatus outcome, String pluginName, String fromVersion, String toVersion, String detail) {

    public static InstallResult of(InstallStatus outcome, String pluginName, String fromVersion, String toVersion) {
        return new InstallResult(outcome, pluginName, fromVersion, toVersion, "");
    }

    public static InstallResult failed(InstallStatus outcome, String pluginName, String detail) {
        return new InstallResult(outcome, pluginName, "", "", detail == null ? "" : detail);
    }
}

