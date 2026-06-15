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
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private int port;

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
    }

    @AfterAll
    void tearDown() {
        server.stop(0);
    }

    @Test
    void successfulCheck(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-ok", new HttpCheckConfiguration(
                "http://localhost:" + port + "/ok", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200, List.<HttpCheckConfiguration.Assertion>of(),
                null, null, null, false
        )).get();

        assertTrue(result.success());
        assertEquals("test-ok", result.name());
        assertEquals("200", result.metadata().get("http.response.status_code"));
        assertNull(result.errorMessage());
    }

    @Test
    void timeout(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-timeout", new HttpCheckConfiguration(
                "http://localhost:" + port + "/slow", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT0.1S", 200, List.<HttpCheckConfiguration.Assertion>of(),
                null, null, null, false
        )).get();

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("java.net.http.HttpTimeoutException"), result::errorMessage);
    }

    @Test
    void notFound(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-404", new HttpCheckConfiguration(
                "http://localhost:" + port + "/not-found", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200, List.<HttpCheckConfiguration.Assertion>of(),
                null, null, null, false
        )).get();

        assertFalse(result.success());
        assertEquals("404", result.metadata().get("http.response.status_code"));
    }

    @Test
    void customExpectedStatus(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-404-custom", new HttpCheckConfiguration(
                "http://localhost:" + port + "/not-found", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 404, List.<HttpCheckConfiguration.Assertion>of(),
                null, null, null, false
        )).get();

        assertTrue(result.success());
        assertEquals("404", result.metadata().get("http.response.status_code"));
    }

    @Test
    void jsonAssertionEquals(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.EQUALS, "42")),
                null, null, null, false
        )).get();

        assertTrue(result.success());
    }

    @Test
    void jsonAssertionGte(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.GTE, "40")),
                null, null, null, false
        )).get();

        assertTrue(result.success());
    }

    @Test
    void jsonAssertionGteFail(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-assert-fail", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert-fail", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.GTE, "20")),
                null, null, null, false
        )).get();

        assertFalse(result.success());
    }

    @Test
    void jsonAssertionFailure(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-assert-fail", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.EQUALS, "99")),
                null, null, null, false
        )).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Assertion failed"));
    }

    @Test
    void jsonAssertionExists(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/score", HttpCheckConfiguration.AssertionOperator.EXISTS, "true")),
                null, null, null, false
        )).get();

        assertTrue(result.success());
    }

    @Test
    void jsonAssertionNotExists(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-assert", new HttpCheckConfiguration(
                "http://localhost:" + port + "/assert", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/missing", HttpCheckConfiguration.AssertionOperator.EXISTS, "true")),
                null, null, null, false
        )).get();

        assertFalse(result.success());
    }

    @Test
    void connectionError(@Fusion final HttpCheck httpCheck) throws Exception {
        final var badPort = 19876;
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-error", new HttpCheckConfiguration(
                "http://localhost:" + badPort + "/nope", HTTP_1_1, "GET", false,
                Map.<String, String>of(), "", "PT5S", 200, List.<HttpCheckConfiguration.Assertion>of(),
                null, null, null, false
        )).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void withHeaders(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-headers", new HttpCheckConfiguration(
                "http://localhost:" + port + "/headers", HTTP_1_1, "GET", false,
                Map.of("Authorization", "Bearer test123"), "", "PT10S", 200,
                List.of(new HttpCheckConfiguration.Assertion("/auth", HttpCheckConfiguration.AssertionOperator.EQUALS, "Bearer test123")),
                null, null, null, false
        )).get();

        assertTrue(result.success());
    }
}
