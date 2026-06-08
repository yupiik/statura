/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.statura.otel;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.statura.model.CheckResult;
import io.yupiik.statura.otel.OpenTelemetry.Protocol;
import io.yupiik.statura.ssl.SslConfiguration;
import io.yupiik.statura.ssl.SslContextService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class OtlpMetricsFlusher {
    private final JsonMapper mapper;
    private final ProtobufSerializer protobufSerializer;
    private final SslContextService sslContextService;

    public OtlpMetricsFlusher(final JsonMapper mapper,
                              final ProtobufSerializer protobufSerializer,
                              final SslContextService sslContextService) {
        this.mapper = mapper;
        this.protobufSerializer = protobufSerializer;
        this.sslContextService = sslContextService;
    }

    public void flush(final OpenTelemetry openTelemetry, final List<CheckResult> results) {
        if (results.isEmpty()) {
            return;
        }

        final var prometheusCleaner = Pattern.compile("[^a-zA-Z0-9_:]");

        final var resourceAttributes = openTelemetry.resourceAttributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> attr(e.getKey(), e.getValue()))
                .toList();

        final var mergeableAttributes = switch (openTelemetry.flattenAttributes()) {
            case NONE -> List.of();
            case RESOURCE -> resourceAttributes;
            case SCOPE -> openTelemetry.scopeAttributes().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> attr(e.getKey(), e.getValue()))
                    .toList();
            case ALL -> Stream.concat(
                            resourceAttributes.stream(),
                            openTelemetry.scopeAttributes().entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(e -> attr(e.getKey(), e.getValue())))
                    .toList();
        };
        final var payload = Map.of(
                "resourceMetrics", List.of(Map.of(
                        "resource", Map.of(
                                "attributes", resourceAttributes),
                        "scopeMetrics", List.of(Map.of(
                                "scope", openTelemetry.scopeAttributes(),
                                "metrics", results.stream()
                                        .flatMap(check -> {
                                            final var baseAttributes = Stream.of(
                                                            Stream.of(
                                                                    attr("check_status", check.success() ? "ok" : "ko")),
                                                            Stream.concat(
                                                                    check.success()
                                                                            ? Stream.empty()
                                                                            // note we would prefer a low cardinality error but for this particular app it should be ok
                                                                            : Stream.of(check.errorMessage())
                                                                            .flatMap(error -> Stream.of(
                                                                                    attr("error.message", error))),
                                                                    check.metadata().entrySet().stream()
                                                                            .sorted(Map.Entry.comparingByKey())
                                                                            .map(e -> attr(e.getKey(), e.getValue()))
                                                            ),
                                                            mergeableAttributes.stream())
                                                    .flatMap(identity())
                                                    .toList();

                                            final var ts = check.timestampNanos();
                                            final var metricName = prometheusCleaner.matcher(check.name()).replaceAll("_");

                                            return Stream.of(
                                                    // duration
                                                    Map.of(
                                                            "name", metricName + "_duration",
                                                            "unit", "ms",
                                                            "gauge", Map.of(
                                                                    "dataPoints", List.of(Map.of(
                                                                            "timeUnixNano", ts,
                                                                            "asDouble", (double) check.durationMs(),
                                                                            "attributes", baseAttributes)))),
                                                    // status
                                                    Map.of(
                                                            "name", metricName + "_status",
                                                            "unit", "1",
                                                            "gauge", Map.of(
                                                                    "dataPoints", List.of(Map.of(
                                                                            "timeUnixNano", ts,
                                                                            "asInt", check.success() ? 1 : 0,
                                                                            "attributes", baseAttributes)))),
                                                    // execution (flag as being executed to identify misses)
                                                    Map.of(
                                                            "name", metricName + "_results",
                                                            "unit", "1",
                                                            "sum", Map.of(
                                                                    "dataPoints", List.of(Map.of(
                                                                            "timeUnixNano", ts,
                                                                            "asInt", 1,
                                                                            "attributes", baseAttributes)),
                                                                    "aggregationTemporality", 2,
                                                                    "isMonotonic", false)));
                                        })
                                        .toList())))));

        final var httpBuilder = HttpClient.newBuilder()
                .version(openTelemetry.httpVersion());

        final var sslContext = sslContextService.buildSslContext(new SslConfiguration(
                openTelemetry.sslCertificates(),
                openTelemetry.sslClientCertificate(),
                openTelemetry.sslClientPrivateKey()));
        if (sslContext != null) {
            httpBuilder.sslContext(sslContext);
        }

        try (final var client = httpBuilder.build()) {
            final var isProtobuf = openTelemetry.protocol() == Protocol.PROTOBUF;

            final var request = HttpRequest.newBuilder()
                    .uri(URI.create(openTelemetry.endpoint()))
                    .header("content-type",
                            isProtobuf ? "application/x-protobuf" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            isProtobuf ? serializeProtobuf(payload) : mapper.toBytes(payload)));
            openTelemetry.headers().forEach(request::header);
            final var response = client.send(request.build(), ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OTEL collector returned " + response.statusCode() + ": " + response.body());
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to send metrics to OTEL collector '" + openTelemetry.endpoint() + "':\n" + payload, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeProtobuf(final Map<?, ?> payload) {
        final var resourceMetricsList = (List<Map<String, Object>>) payload.get("resourceMetrics");
        final var serializedRMs = resourceMetricsList.stream()
                .map(this::serializeResourceMetrics)
                .toList();
        return protobufSerializer.exportMetricsServiceRequest(serializedRMs);
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeResourceMetrics(final Map<String, Object> rm) {
        final var resource = (Map<String, Object>) rm.get("resource");
        final var scopeMetricsList = (List<Map<String, Object>>) rm.get("scopeMetrics");
        final var serializedAttrs = serializeAttributes((List<Map<String, Object>>) resource.get("attributes"));
        return protobufSerializer.resourceMetrics(
                protobufSerializer.resource(serializedAttrs),
                scopeMetricsList.stream().map(this::serializeScopeMetrics).toList());
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeScopeMetrics(final Map<String, Object> sm) {
        final var scope = (Map<String, Object>) sm.get("scope");
        final var metrics = (List<Map<String, Object>>) sm.get("metrics");
        return protobufSerializer.scopeMetrics(
                protobufSerializer.instrumentationScope(
                        (String) scope.get("name"),
                        (String) scope.get("version")),
                metrics.stream().map(this::serializeMetric).toList());
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeMetric(final Map<String, Object> metric) {
        final var name = (String) metric.get("name");
        final var unit = (String) metric.get("unit");
        if (metric.containsKey("gauge")) {
            final var gauge = (Map<String, Object>) metric.get("gauge");
            final var dps = (List<Map<String, Object>>) gauge.get("dataPoints");
            return protobufSerializer.gaugeMetric(name, unit,
                    dps.stream().map(this::serializeDataPoint).toList());
        }
        final var sum = (Map<String, Object>) metric.get("sum");
        final var dps = (List<Map<String, Object>>) sum.get("dataPoints");
        final var isMonotonic = (boolean) sum.get("isMonotonic");
        return protobufSerializer.sumMetric(name, unit,
                dps.stream().map(this::serializeDataPoint).toList(), isMonotonic);
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeDataPoint(final Map<String, Object> dp) {
        final var attrs = (List<Map<String, Object>>) dp.get("attributes");
        return protobufSerializer.numberDataPoint(
                ((Number) dp.get("timeUnixNano")).longValue(),
                dp.containsKey("asDouble") ? ((Number) dp.get("asDouble")).doubleValue() : 0.0,
                dp.containsKey("asInt") ? ((Number) dp.get("asInt")).longValue() : 0L,
                serializeAttributes(attrs));
    }

    private List<byte[]> serializeAttributes(final List<Map<String, Object>> attributes) {
        return attributes.stream().map(this::serializeKeyValue).toList();
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeKeyValue(final Map<String, Object> kv) {
        final var key = (String) kv.get("key");
        final var value = (Map<String, Object>) kv.get("value");
        final byte[] serializedValue;
        if (value.containsKey("stringValue")) {
            serializedValue = protobufSerializer.anyValueString((String) value.get("stringValue"));
        } else if (value.containsKey("intValue")) {
            serializedValue = protobufSerializer.anyValueInt(((Number) value.get("intValue")).longValue());
        } else {
            serializedValue = protobufSerializer.anyValueDouble(((Number) value.get("doubleValue")).doubleValue());
        }
        return protobufSerializer.keyValue(key, serializedValue);
    }

    private Map<String, Object> attr(final String key, final Object value) {
        final var v = value instanceof Number n ?
                Map.of(n instanceof Double || n instanceof Float ? "doubleValue" : "intValue", n) :
                Map.of("stringValue", value.toString());
        return Map.of("key", key, "value", v);
    }
}
