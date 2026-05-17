package io.yupiik.statura;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.statura.check.HttpCheck;
import io.yupiik.statura.check.HttpCheckConfiguration;
import io.yupiik.statura.otel.OpenTelemetry;
import io.yupiik.statura.otel.OtlpMetricsFlusher;
import io.yupiik.statura.otel.ProtobufSerializer;
import io.yupiik.statura.ssl.SslContextService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
class CheckExecutorTest {
    private static HttpServer targetServer;
    private static HttpServer collectorServer;
    private static final CopyOnWriteArrayList<String> collectorRequests = new CopyOnWriteArrayList<>();
    private static int targetPort;
    private static int collectorPort;

    @BeforeAll
    static void setUp() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/ok", e -> {
            final var body = "{\"status\":\"ok\"}".getBytes(UTF_8);
            e.getResponseHeaders().add("content-type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        targetServer.start();
        targetPort = targetServer.getAddress().getPort();

        collectorServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        collectorServer.createContext("/v1/metrics", e -> {
            final var body = new String(e.getRequestBody().readAllBytes(), UTF_8);
            collectorRequests.add(body);
            e.sendResponseHeaders(200, 0);
            e.close();
        });
        collectorServer.start();
        collectorPort = collectorServer.getAddress().getPort();
    }

    @AfterAll
    static void tearDown() {
        collectorServer.stop(0);
        targetServer.stop(0);
    }

    @Test
    void runHttpCheckAndFlushMetrics() throws Exception {
        try (final var mapper = new JsonMapperImpl(List.of(), c -> empty())) {
            final var sslContextService = new SslContextService();
            final var httpCheck = new HttpCheck(mapper, Clock.systemUTC(), sslContextService);
            final var flusher = new OtlpMetricsFlusher(mapper, new ProtobufSerializer(), sslContextService);

            final var checkConfig = new HttpCheckConfiguration(
                    "http://localhost:" + targetPort + "/ok",
                    Version.HTTP_1_1, "GET", false, Map.<String, String>of(), "", "PT10S", 200, List.<HttpCheckConfiguration.Assertion>of(), null, null, null);

            final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "smoke-test", checkConfig).get();
            assertTrue(result.success());

            final var results = List.of(result);
            flusher.flush(new OpenTelemetry("http://localhost:" + collectorPort + "/v1/metrics", Map.of(),
                    OpenTelemetry.Protocol.JSON, HttpClient.Version.HTTP_1_1, List.of(), "", "",
                    Map.of("service.name", "statura", "service.version", "1.0"),
                    Map.of("name", "statura", "version", "1.0")), results);

            assertEquals(1, collectorRequests.size());
            final var payload = collectorRequests.getFirst();
            assertTrue(payload.contains("smoke_test"));
        }
    }
}
