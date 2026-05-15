package io.yupiik.statura.model;

import java.util.Map;

public record CheckResult(
        String name,
        Map<String, String> metadata,
        long durationMs,
        long timestampNanos,
        boolean success,
        String errorMessage
) {
}
