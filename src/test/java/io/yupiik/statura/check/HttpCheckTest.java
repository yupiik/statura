package io.yupiik.statura.check;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@FusionSupport
@TestInstance(PER_CLASS)
class HttpCheckTest {
    private HttpServer server;
    private HttpClient client;
    private int port;

    @Fusion
    private HttpCheck httpCheck;

    @BeforeAll
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ok", e -> {
            final var body = "{\"status\":\"ok\"}".getBytes(UTF_8);
            e.getResponseHeaders().add("content-type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        server.createContext("/not-found", e -> {
            e.sendResponseHeaders(404, -1);
            e.close();
        });
        server.createContext("/assert", e -> {
            final var body = "{\"score\":42}".getBytes(UTF_8);
            e.getResponseHeaders().add("content-type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        server.createContext("/assert-fail", e -> {
            final var body = "{\"score\":10}".getBytes(UTF_8);
            e.getResponseHeaders().add("content-type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        server.createContext("/slow", e -> {
            try {
                Thread.sleep(500);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            final var body = "{}".getBytes(UTF_8);
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        server.createContext("/headers", e -> {
            final var auth = e.getRequestHeaders().getFirst("Authorization");
            final var body = ("{\"auth\":\"" + (auth != null ? auth : "") + "\"}").getBytes(UTF_8);
            e.getResponseHeaders().add("content-type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        server.start();
        port = server.getAddress().getPort();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @AfterAll
    void tearDown() {
        server.stop(0);
    }

    @Test
    void successfulCheck() throws Exception {
        final var result = httpCheck.check(client, "test-ok", new HttpCheckConfiguration(
                "http://localhost:" + port + "/ok", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200, List.of()
        )).get();

        assertTrue(result.success());
        assertEquals("test-ok", result.name());
        assertEquals(200, result.statusCode());
        assertNull(result.errorMessage());
    }

    @Test
    void timeout() throws Exception {
        final var result = httpCheck.check(client, "test-timeout", new HttpCheckConfiguration(
                "http://localhost:" + port + "/slow", HTTP_1_1, "GET",
                Map.of(), "", "PT0.1S", 200, List.of()
        )).get();

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("java.net.http.HttpTimeoutException"), result::errorMessage);
    }

    @Test
    void notFound() throws Exception {
        final var result = httpCheck.check(client, "test-404", new HttpCheckConfiguration(
                "http://localhost:" + port + "/not-found", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200, List.of()
        )).get();

        assertFalse(result.success());
        assertEquals(404, result.statusCode());
    }

    @Test
    void customExpectedStatus() throws Exception {
        final var result = httpCheck.check(client, "test-404-custom", new HttpCheckConfiguration(
                "http://localhost:" + port + "/not-found", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 404, List.of()
        )).get();

        assertTrue(result.success());
        assertEquals(404, result.statusCode());
    }

    @Test
    void jsonAssertionEquals() throws Exception {
        final var result = httpCheck.check(client, "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.EQUALS, "42"))
        )).get();

        assertTrue(result.success());
    }

    @Test
    void jsonAssertionGte() throws Exception {
        final var result = httpCheck.check(client, "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.GTE, "40"))
        )).get();

        assertTrue(result.success());
    }

    @Test
    void jsonAssertionGteFail() throws Exception {
        final var result = httpCheck.check(client, "test-assert-fail", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert-fail", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.GTE, "20"))
        )).get();

        assertFalse(result.success());
    }

    @Test
    void jsonAssertionFailure() throws Exception {
        final var result = httpCheck.check(client, "test-assert-fail", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.EQUALS, "99"))
        )).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Assertion failed"));
    }

    @Test
    void jsonAssertionExists() throws Exception {
        final var result = httpCheck.check(client, "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.EXISTS, "true"))
        )).get();

        assertTrue(result.success());
    }

    @Test
    void jsonAssertionNotExists() throws Exception {
        final var result = httpCheck.check(client, "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET",
                Map.of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/missing", HttpCheckConfiguration.AssertionOperator.EXISTS, "true"))
        )).get();

        assertFalse(result.success());
    }

    @Test
    void connectionError() throws Exception {
        final var badPort = 19876;
        final var result = httpCheck.check(client, "test-error", new HttpCheckConfiguration(
                "http://localhost:" + badPort + "/nope", HTTP_1_1, "GET",
                Map.of(), "", "PT5S", 200, List.of()
        )).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void withHeaders() throws Exception {
        final var result = httpCheck.check(client, "test-headers", new HttpCheckConfiguration(
                "http://localhost:" + port + "/headers", HTTP_1_1, "GET",
                Map.of("Authorization", "Bearer test123"), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/auth", HttpCheckConfiguration.AssertionOperator.EQUALS, "Bearer test123"))
        )).get();

        assertTrue(result.success());
    }
}
