package io.yupiik.statura.check;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@FusionSupport
@TestInstance(PER_CLASS)
class HttpCheckSslTest {
    private HttpsServer server;
    private int port;
    private String serverCertPem;
    private String serverKeyPem;

    @BeforeAll
    void setUp() throws Exception {
        final var ksFile = Files.createTempFile("https-test", ".jks");
        Files.delete(ksFile);
        try {
            final var pb = new ProcessBuilder(
                    "keytool", "-genkeypair", "-alias", "test",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-dname", "CN=localhost, O=Test, C=US",
                    "-ext", "SAN=dns:localhost",
                    "-storepass", "password", "-keypass", "password",
                    "-keystore", ksFile.toAbsolutePath().toString(),
                    "-storetype", "JKS");
            pb.redirectErrorStream(true);
            final var keytoolProcess = pb.start();
            assertTrue(keytoolProcess.waitFor(30, TimeUnit.SECONDS),
                    "keytool should finish within timeout");
            final var keytoolOut = new String(keytoolProcess.getInputStream().readAllBytes());
            assertEquals(0, keytoolProcess.exitValue(), "keytool failed: " + keytoolOut);

            final var ks = KeyStore.getInstance("JKS");
            try (var in = Files.newInputStream(ksFile)) {
                ks.load(in, "password".toCharArray());
            }

            final var cert = (X509Certificate) ks.getCertificate("test");
            serverCertPem = "-----BEGIN CERTIFICATE-----\n" +
                    Base64.getMimeEncoder().encodeToString(cert.getEncoded()) +
                    "\n-----END CERTIFICATE-----";

            final var key = (PrivateKey) ks.getKey("test", "password".toCharArray());
            serverKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder().encodeToString(key.getEncoded()) +
                    "\n-----END PRIVATE KEY-----";

            final var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "password".toCharArray());

            final var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            server.createContext("/ok", e -> {
                final var body = "{\"status\":\"ok\"}".getBytes(UTF_8);
                e.getResponseHeaders().add("content-type", "application/json");
                e.sendResponseHeaders(200, body.length);
                e.getResponseBody().write(body);
                e.close();
            });
            server.start();
            port = server.getAddress().getPort();
        } finally {
            Files.deleteIfExists(ksFile);
        }
    }

    @AfterAll
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void httpsWithCustomTrust(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-https",
                new HttpCheckConfiguration(
                        "https://localhost:" + port + "/ok", HTTP_1_1, "GET", false,
                        Map.of(), "", "PT10S", 200, List.of(),
                        List.of(serverCertPem), null, null)).get();

        assertTrue(result.success());
        assertEquals("test-https", result.name());
        assertEquals("200", result.metadata().get("http.response.status_code"));
        assertNull(result.errorMessage());
    }

    @Test
    void httpsWithoutCustomTrustFails(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-https-fail",
                new HttpCheckConfiguration(
                        "https://localhost:" + port + "/ok", HTTP_1_1, "GET", false,
                        Map.of(), "", "PT10S", 200, List.of(),
                        null, null, null)).get();

        assertNotNull(result.errorMessage());
    }

    @Test
    void httpsWithMtls(@Fusion final HttpCheck httpCheck) throws Exception {
        final var result = httpCheck.check(List.of(), Runnable::run, Duration.ofSeconds(30), "test-mtls",
                new HttpCheckConfiguration(
                        "https://localhost:" + port + "/ok", HTTP_1_1, "GET", false,
                        Map.of(), "", "PT10S", 200, List.of(),
                        List.of(serverCertPem), serverCertPem, serverKeyPem)).get();

        assertTrue(result.success());
        assertEquals("200", result.metadata().get("http.response.status_code"));
        assertNull(result.errorMessage());
    }
}
