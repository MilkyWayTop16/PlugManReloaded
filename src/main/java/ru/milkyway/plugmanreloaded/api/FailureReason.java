package ru.milkyway.plugmanreloaded.api;

public enum FailureReason {

    PLUGIN_NOT_FOUND("errors.plugin-not-found"),
    FILE_NOT_FOUND("errors.file-not-found"),
    INVALID_PLUGIN("errors.invalid-plugin"),
    INVALID_DESCRIPTION("errors.invalid-description"),
    JAR_CORRUPTED("errors.jar-corrupted"),
    DUPLICATE_PLUGIN("errors.duplicate-plugin"),

    LOAD_FAILED("errors.load-failed"),
    UNLOAD_FAILED("errors.unload-failed"),
    RELOAD_FAILED("errors.reload-failed"),
    ENABLE_FAILED("errors.enable-failed"),
    DISABLE_FAILED("errors.disable-failed"),
    RESTART_LEFT_UNLOADED("errors.restart-left-unloaded"),
    DELETE_FAILED("delete.failed-file"),

    ALREADY_ENABLED("errors.already-enabled"),
    ALREADY_DISABLED("errors.already-disabled"),
    HAS_DEPENDENTS("errors.has-dependents"),
    MISSING_DEPENDENCIES("errors.missing-deps"),
    CIRCULAR_DEPENDENCIES("errors.circular-deps"),

    SELF_PROTECTED("errors.self-protected"),
    PLUGIN_IGNORED("errors.plugin-ignored"),
    OPERATION_CANCELLED("errors.operation-cancelled"),
    TIMEOUT("errors.timeout"),

    UNSUPPORTED_JAVA("errors.unsupported-java"),
    UNSUPPORTED_API("errors.unsupported-api"),
    INCOMPATIBLE_CORE("errors.incompatible-core"),
    INCOMPATIBLE_CLASS_CHANGE("errors.incompatible-class-change"),
    PAPER_BOOTSTRAPPER("errors.paper-bootstrapper"),
    CLASS_NOT_FOUND("errors.class-not-found"),
    DATABASE_LOCKED("errors.database-locked"),
    PORT_IN_USE("errors.port-in-use"),

    INTERNAL_ERROR("errors.internal-error"),
    UNKNOWN("errors.load-failed");

    private final String messageKey;

    FailureReason(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }

    public static FailureReason ofKey(String messageKey) {
        if (messageKey != null) {
            for (FailureReason reason : values()) {
                if (reason.messageKey.equals(messageKey)) {
                    return reason;
                }
            }
        }
        return UNKNOWN;
    }
}
