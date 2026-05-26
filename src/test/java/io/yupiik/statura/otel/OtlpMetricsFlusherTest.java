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

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.statura.model.CheckResult;
import io.yupiik.statura.otel.OpenTelemetry.Protocol;
import io.yupiik.statura.ssl.SslContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpMetricsFlusherTest {
    private HttpServer collector;
    private final List<String> capturedRequests = new CopyOnWriteArrayList<>();
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        collector = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        collector.createContext("/v1/metrics", e -> {
            final var body = new String(e.getRequestBody().readAllBytes(), UTF_8);
            capturedRequests.add(body);
            e.sendResponseHeaders(200, 0);
            e.close();
        });
        collector.start();
        port = collector.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        collector.stop(0);
    }

    @Test
    void flushEmptyResultsDoesNothing() {
        try (final var mapper = new JsonMapperImpl(List.of(), _ -> empty())) {
            final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), new SslContextService());
            flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v1/metrics", Map.of(), Protocol.JSON, HttpClient.Version.HTTP_1_1, List.of(), "", "",
                    Map.of("service.name", "statura", "service.version", "1.0"),
                    Map.of("name", "statura", "version", "1.0")), List.of());
            assertEquals(0, capturedRequests.size());
        }
    }

    @Test
    void flushSingleResult() {
        try (final var mapper = new JsonMapperImpl(List.of(), _ -> empty())) {
            final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), new SslContextService());
            final var results = List.of(new CheckResult(
                    "test-check", Map.of("url", "http://example.com"), 42, System.nanoTime(), true, null));
            flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v1/metrics", Map.of(), Protocol.JSON, HttpClient.Version.HTTP_1_1, List.of(), "", "",
                    Map.of("service.name", "statura", "service.version", "1.0"),
                    Map.of("name", "statura", "version", "1.0")), results);

            assertEquals(1, capturedRequests.size());
            final var payload = capturedRequests.getFirst();
            assertTrue(payload.contains("test_check"));
            assertTrue(payload.contains("http://example.com"));
        }
    }

    @Test
    void flushMultipleResults() {
        try (final var mapper = new JsonMapperImpl(List.of(), _ -> empty())) {
            final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), new SslContextService());
            final var results = List.of(
                    new CheckResult("ok-check", Map.of("url", "http://ok", "status", "200"), 10, 1000L, true, null),
                    new CheckResult("ko-check", Map.of("url", "http://ko", "status", "500"), 20, 2000L, false, "error"));
            flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v1/metrics", Map.of(), Protocol.JSON, HttpClient.Version.HTTP_1_1, List.of(), "", "",
                    Map.of("service.name", "statura", "service.version", "1.0"),
                    Map.of("name", "statura", "version", "1.0")), results);

            assertEquals(1, capturedRequests.size());
            final var payload = capturedRequests.getFirst();
            assertTrue(payload.contains("ok_check"));
            assertTrue(payload.contains("ko_check"));
            assertTrue(payload.contains("http://ok"));
            assertTrue(payload.contains("http://ko"));
        }
    }

    @Test
    void errorOnNon200Response() throws IOException {
        final var errCollector = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        try {
            errCollector.createContext("/v1/metrics", e -> {
                e.sendResponseHeaders(500, 0);
                e.close();
            });
            errCollector.start();
            final var errPort = errCollector.getAddress().getPort();

            try (final var mapper = new JsonMapperImpl(List.of(), _ -> empty())) {
                final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), new SslContextService());
                final var results = List.of(new CheckResult(
                        "test", Map.of("url", "http://example.com"), 10, 1000L, true, null));
                final var ex = assertThrows(IllegalStateException.class,
                        () -> flusher.flush(new OpenTelemetry("http://localhost:" + errPort + "/v1/metrics", Map.of(), Protocol.JSON, HttpClient.Version.HTTP_1_1, List.of(), "", "",
                                Map.of("service.name", "statura", "service.version", "1.0"),
                                Map.of("name", "statura", "version", "1.0")), results));
                assertTrue(ex.getMessage().contains("500"));
            }
        } finally {
            errCollector.stop(0);
        }
    }

    @Test
    void flushWithHeaders() {
        collector.createContext("/v2/metrics", e -> {
            final var body = new String(e.getRequestBody().readAllBytes(), UTF_8);
            capturedRequests.add(body);
            e.sendResponseHeaders(200, 0);
            e.close();
        });

        try (final var mapper = new JsonMapperImpl(List.of(), _ -> empty())) {
            final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), new SslContextService());
            final var results = List.of(new CheckResult(
                    "test", Map.of("url", "http://example.com"), 10, 1000L, true, null));
            flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v2/metrics", Map.of(), Protocol.JSON, HttpClient.Version.HTTP_1_1, List.of(), "", "",
                    Map.of("service.name", "statura", "service.version", "1.0"),
                    Map.of("name", "statura", "version", "1.0")), results);

            assertEquals(1, capturedRequests.size());
        }
    }

    @Test
    void flushProtobuf() {
        try (final var mapper = new JsonMapperImpl(List.of(), _ -> empty())) {
            final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), new SslContextService());
            final var results = List.of(
                    new CheckResult("pb-check", Map.of("url", "http://pb", "status", "200"), 15, 3000L, true, null));
            flusher.flush(new OpenTelemetry(
                    "http://localhost:" + port + "/v1/metrics", Map.of(), Protocol.PROTOBUF, HttpClient.Version.HTTP_1_1,
                    List.of(), "", "", Map.of("service.name", "statura", "service.version", "1.0"),
                    Map.of("name", "statura", "version", "1.0")), results);

            assertEquals(1, capturedRequests.size());
            final var body = capturedRequests.getFirst();
            assertFalse(body.isEmpty(), body);
            assertFalse(body.startsWith("{"), "protobuf body should not be JSON");
        }
    }

    @Test
    void flushProtobufProducesNonEmptyBinary() {
        try (final var mapper = new JsonMapperImpl(List.of(), _ -> empty())) {
            final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), new SslContextService());
            final var results = List.of(new CheckResult(
                    "pb-bin", Map.of("url", "http://pb"), 10, 1000L, true, null));
            flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v1/metrics", Map.of(), Protocol.PROTOBUF,
                    HttpClient.Version.HTTP_1_1, List.of(), "", "",
                    Map.of("service.name", "statura", "service.version", "1.0"),
                    Map.of("name", "statura", "version", "1.0")), results);
            assertEquals(1, capturedRequests.size());
            final var body = capturedRequests.getFirst();
            assertFalse(body.isEmpty());
        }
    }
}
