package ru.milkyway.plugmanreloaded.api;

public final class PlugManProvider {
    private static volatile PlugManAPI instance;

    private PlugManProvider() {}

    public static boolean isAvailable() {
        return instance != null;
    }

    public static PlugManAPI get() {
        if (instance == null) {
            throw new IllegalStateException("PlugManReloaded API is not initialized or the plugin is disabled");
        }
        return instance;
    }

    public static void register(PlugManAPI api) {
        instance = api;
    }

    public static void unregister() {
        instance = null;
    }
}

