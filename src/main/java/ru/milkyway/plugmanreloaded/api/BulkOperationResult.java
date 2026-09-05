package ru.milkyway.plugmanreloaded.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record BulkOperationResult(
        int total,
        int successCount,
        List<String> successfulPlugins,
        List<String> failedPlugins,
        Map<String, String> failureReasons,
        long elapsedMillis
) {
    public static BulkOperationResult empty() {
        return new BulkOperationResult(0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), 0L);
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public int failedCount() {
        return failedPlugins.size();
    }

    public boolean isAllSuccessful() {
        return total > 0 && failedPlugins.isEmpty();
    }
}

