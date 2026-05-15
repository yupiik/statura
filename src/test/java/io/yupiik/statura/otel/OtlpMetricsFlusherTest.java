package io.yupiik.statura.otel;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.statura.model.CheckResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        final var flusher = new OtlpMetricsFlusher(
                new JsonMapperImpl(List.of(), c -> empty()),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v1/metrics", Map.of()), List.of());
        assertEquals(0, capturedRequests.size());
    }

    @Test
    void flushSingleResult() {
        final var flusher = new OtlpMetricsFlusher(
                new JsonMapperImpl(List.of(), c -> empty()),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        final var results = List.of(new CheckResult(
                "test-check", "http://example.com", 200, 42, System.nanoTime(), true, null));
        flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v1/metrics", Map.of()), results);

        assertEquals(1, capturedRequests.size());
        final var payload = capturedRequests.getFirst();
        assertTrue(payload.contains("http_check_duration_ms"));
        assertTrue(payload.contains("http_check_total"));
        assertTrue(payload.contains("http_check_up"));
        assertTrue(payload.contains("test-check"));
        assertTrue(payload.contains("http://example.com"));
    }

    @Test
    void flushMultipleResults() {
        final var flusher = new OtlpMetricsFlusher(
                new JsonMapperImpl(List.of(), c -> empty()),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        final var results = List.of(
                new CheckResult("ok-check", "http://ok", 200, 10, 1000L, true, null),
                new CheckResult("ko-check", "http://ko", 500, 20, 2000L, false, "error"));
        flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v1/metrics", Map.of()), results);

        assertEquals(1, capturedRequests.size());
        final var payload = capturedRequests.getFirst();
        assertTrue(payload.contains("ok-check"));
        assertTrue(payload.contains("ko-check"));
        assertTrue(payload.contains("http://ok"));
        assertTrue(payload.contains("http://ko"));
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

            final var flusher = new OtlpMetricsFlusher(
                    new JsonMapperImpl(List.of(), c -> empty()),
                    HttpClient.newHttpClient(),
                    Clock.systemUTC());
            final var results = List.of(new CheckResult(
                    "test", "http://example.com", 200, 10, 1000L, true, null));
            final var ex = assertThrows(IllegalStateException.class,
                    () -> flusher.flush(new OpenTelemetry("http://localhost:" + errPort + "/v1/metrics", Map.of()), results));
            assertTrue(ex.getMessage().contains("500"));
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

        final var flusher = new OtlpMetricsFlusher(
                new JsonMapperImpl(List.of(), c -> empty()),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        final var results = List.of(new CheckResult(
                "test", "http://example.com", 200, 10, 1000L, true, null));
        flusher.flush(new OpenTelemetry("http://localhost:" + port + "/v2/metrics", Map.of()), results);

        assertEquals(1, capturedRequests.size());
    }
}
