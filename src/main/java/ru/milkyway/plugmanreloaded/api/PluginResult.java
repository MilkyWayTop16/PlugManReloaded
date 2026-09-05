package ru.milkyway.plugmanreloaded.api;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record PluginResult(
        boolean success,
        String messageKey,
        FailureReason reason,
        Map<String, String> placeholders,
        Throwable error,
        long elapsedMs
) {

    public String detail(String fallback) {
        return placeholders.getOrDefault("error", fallback);
    }

    public static PluginResult ofSuccess(String messageKey, String... placeholders) {
        return new PluginResult(true, messageKey, null, toMap(placeholders), null, 0L);
    }

    public static PluginResult ofSuccess(String messageKey, long elapsedMs, String... placeholders) {
        return new PluginResult(true, messageKey, null, toMap(placeholders), null, elapsedMs);
    }

    public static PluginResult ofSuccess(String messageKey, Map<String, String> placeholders) {
        return new PluginResult(true, messageKey, null, placeholders != null ? placeholders : Map.of(), null, 0L);
    }

    public static PluginResult ofSuccess(String messageKey, Map<String, String> placeholders, long elapsedMs) {
        return new PluginResult(true, messageKey, null, placeholders != null ? placeholders : Map.of(), null, elapsedMs);
    }

    public static PluginResult ofError(String messageKey, Throwable error, Map<String, String> placeholders) {
        return new PluginResult(false, messageKey, FailureReason.ofKey(messageKey),
                placeholders != null ? placeholders : Map.of(), error, 0L);
    }

    public static PluginResult ofError(FailureReason reason, String... placeholders) {
        return new PluginResult(false, reason.messageKey(), reason, toMap(placeholders), null, 0L);
    }

    public static PluginResult ofError(FailureReason reason, Throwable error, String... placeholders) {
        return new PluginResult(false, reason.messageKey(), reason, toMap(placeholders), error, 0L);
    }

    public static PluginResult ofError(FailureReason reason, Throwable error, Map<String, String> placeholders) {
        return new PluginResult(false, reason.messageKey(), reason,
                placeholders != null ? placeholders : Map.of(), error, 0L);
    }

    private static Map<String, String> toMap(@Nullable String[] placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            map.put(placeholders[i], placeholders[i + 1] != null ? placeholders[i + 1] : "");
        }
        return Collections.unmodifiableMap(map);
    }
}
