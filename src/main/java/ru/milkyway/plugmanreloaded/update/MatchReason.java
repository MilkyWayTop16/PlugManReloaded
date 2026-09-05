package ru.milkyway.plugmanreloaded.update;

public enum MatchReason {

    HASH_MATCH("hash"),
    MAIN_MATCH("main-class"),
    PLUGIN_YML_MATCH("plugin-yml"),
    CATALOG("catalog"),
    JAR_REFERENCE("jar-reference"),
    NAME_FUZZY("name"),
    NONE("not-found");

    private static final String PREFIX = "actions.update.match-reasons.";

    private final String key;

    MatchReason(String key) {
        this.key = key;
    }

    public String messageKey() {
        return PREFIX + key;
    }
}
