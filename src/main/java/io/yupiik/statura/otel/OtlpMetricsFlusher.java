package io.yupiik.statura.otel;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.statura.model.CheckResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.stream.Collectors.groupingBy;

@ApplicationScoped
public class OtlpMetricsFlusher {
    private final JsonMapper mapper;
    private final HttpClient client;
    private final Clock clock;

    public OtlpMetricsFlusher(final JsonMapper mapper,
                              final HttpClient client,
                              final Clock clock) {
        this.mapper = mapper;
        this.client = client;
        this.clock = clock;
    }

    public void flush(final OpenTelemetry openTelemetry, final List<CheckResult> results) {
        if (results.isEmpty()) {
            return;
        }

        var successTotal = 0;
        var failureTotal = 0;
        final var durationDataPoints = new ArrayList<Map<String, Object>>();
        final var checkTotalDataPoints = new ArrayList<Map<String, Object>>();

        for (final var r : results) {
            final var attrs = List.of(
                    attr("url", r.url()),
                    attr("check_name", r.name()),
                    attr("status_code", r.statusCode()),
                    attr("status", r.success() ? "ok" : "ko")
            );

            durationDataPoints.add(Map.of(
                    "timeUnixNano", r.timestampNanos(),
                    "asDouble", (double) r.durationMs(),
                    "attributes", attrs
            ));

            checkTotalDataPoints.add(Map.of(
                    "timeUnixNano", r.timestampNanos(),
                    "asInt", 1,
                    "attributes", attrs
            ));

            if (r.success()) successTotal++;
            else failureTotal++;
        }

        final var now = clock.millis() * 1_000_000L;

        final var payload = Map.of(
                "resourceMetrics", List.of(Map.of(
                        "resource", Map.of("attributes", List.of(
                                attr("service.name", "statura"),
                                attr("service.version", "1.0")
                        )),
                        "scopeMetrics", List.of(Map.of(
                                "scope", Map.of("name", "statura", "version", "1.0"),
                                "metrics", List.of(
                                        gaugeMetric("http_check_duration_ms", "ms", durationDataPoints),
                                        sumMetric("http_check_total", "1", checkTotalDataPoints, true),
                                        gaugeMetric("http_check_up", "1", List.of(Map.of(
                                                "timeUnixNano", now,
                                                "asInt", successTotal,
                                                "attributes", List.of(attr("status", "ok"))
                                        ), Map.of(
                                                "timeUnixNano", now,
                                                "asInt", failureTotal,
                                                "attributes", List.of(attr("status", "ko"))
                                        )))
                                )
                        ))
                ))
        );

        try {
            final var body = mapper.toBytes(payload);
            final var request = HttpRequest.newBuilder()
                    .uri(URI.create(openTelemetry.endpoint()))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            final var response = client.send(request, ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OTEL collector returned " + response.statusCode() + ": " + response.body());
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to send metrics to OTEL collector", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> gaugeMetric(final String name, final String unit, final List<Map<String, Object>> dataPoints) {
        final var metric = new LinkedHashMap<String, Object>();
        metric.put("name", name);
        metric.put("unit", unit);
        metric.put("gauge", Map.of("dataPoints", dataPoints));
        return metric;
    }

    private Map<String, Object> sumMetric(final String name, final String unit, final List<Map<String, Object>> dataPoints, final boolean monotonic) {
        final var metric = new LinkedHashMap<String, Object>();
        metric.put("name", name);
        metric.put("unit", unit);
        metric.put("sum", Map.of(
                "dataPoints", dataPoints,
                "isMonotonic", monotonic,
                "aggregationTemporality", 2
        ));
        return metric;
    }

    private Map<String, Object> attr(final String key, final Object value) {
        final var v = value instanceof Number n ?
                Map.of(n instanceof Double || n instanceof Float ? "doubleValue" : "intValue", n) :
                Map.of("stringValue", value.toString());
        return Map.of("key", key, "value", v);
    }
}
