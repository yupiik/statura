package io.yupiik.statura.otel;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.Map;

public record OpenTelemetry(
        @Property(documentation = "OpenTelemetry collector HTTP (JSON) endpoint for metrics.")
        String endpoint,

        @Property(documentation = "Headers to send to respect OpenTelemetry collector endpoint.")
        Map<String, String> headers
) {
    public static final String DEFAULT_ENDPOINT = "http://localhost:4318/v1/metrics";
}
