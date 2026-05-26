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

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@FusionSupport
@TestInstance(PER_CLASS)
class X509CheckTest {
    private SSLServerSocket serverSocket;
    private int port;
    private String fingerprint;
    private Thread serverThread;

    private Path ksFile;

    @BeforeAll
    void setUp() throws Exception {
        ksFile = Files.createTempFile("x509test", "jks");
        Files.delete(ksFile);

        final var pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-dname", "CN=localhost, O=Test, C=US",
                "-ext", "SAN=dns:localhost",
                "-storepass", "password",
                "-keypass", "password",
                "-keystore", ksFile.toAbsolutePath().toString(),
                "-storetype", "JKS");
        pb.redirectErrorStream(true);
        final var keytoolProcess = pb.start();
        assertTrue(keytoolProcess.waitFor(1, TimeUnit.MINUTES), "keytool should finish within timeout");
        final var keytoolOut = new String(keytoolProcess.getInputStream().readAllBytes());
        assertEquals(0, keytoolProcess.exitValue(), "keytool failed: " + keytoolOut);

        final var ks = KeyStore.getInstance("JKS");
        try (var fis = Files.newInputStream(ksFile)) {
            ks.load(fis, "password".toCharArray());
        }

        final var cert = (X509Certificate) ks.getCertificate("test");
        final var md = MessageDigest.getInstance("SHA-256");
        final var digest = md.digest(cert.getEncoded());
        final var sb = new StringBuilder(digest.length * 3 - 1);
        for (final var b : digest) {
            if (!sb.isEmpty()) {
                sb.append(':');
            }
            sb.append(String.format("%02X", b));
        }
        fingerprint = sb.toString();

        final var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "password".toCharArray());

        final var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        serverSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(0);
        serverSocket.setNeedClientAuth(false);
        port = serverSocket.getLocalPort();

        serverThread = Thread.ofPlatform().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (var client = (SSLSocket) serverSocket.accept()) {
                    client.setSoTimeout(5000);
                    client.startHandshake();
                } catch (final Exception e) {
                    break;
                }
            }
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        Files.deleteIfExists(ksFile);
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(5000);
        }
    }

    @Test
    void successful(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-x509-ok", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, null, null, 0, true, false)).get();

        assertTrue(result.success());
        assertEquals("test-x509-ok", result.name());
        assertNull(result.errorMessage());
        assertEquals(fingerprint, result.metadata().get("fingerprint"));
    }

    @Test
    void expectedSubjectMatch(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-subject", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                "CN=localhost.*", null, null, null, 0, true, false)).get();

        assertTrue(result.success());
    }

    @Test
    void expectedSubjectMismatch(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-subject-fail", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                "CN=evil.*", null, null, null, 0, true, false)).get();

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Subject"));
    }

    @Test
    void expectedFingerprintMatch(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-fp", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, fingerprint, null, 0, true, false)).get();

        assertTrue(result.success());
    }

    @Test
    void expectedFingerprintMismatch(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-fp-fail", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, "AA:BB:CC:DD", null, 0, true, false)).get();

        assertFalse(result.success());
    }

    @Test
    void expectedSans(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-sans", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, null, List.of("dns:localhost"), 0, true, false)).get();

        assertTrue(result.success());
    }

    @Test
    void expectedSansMismatch(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-sans-fail", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, null, List.of("dns:evil.com"), 0, true, false)).get();

        assertFalse(result.success());
    }

    @Test
    void hostnameVerification(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-hostname", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, null, null, 0, true, true)).get();

        assertTrue(result.success(), "Hostname 'localhost' should match SAN 'dns:localhost'");
    }

    @Test
    void hostnameVerificationFails(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-hostname-fail", new X509CheckConfiguration(
                "127.0.0.1", port, "PT10S", null,
                null, null, null, null, 0, true, true)).get();

        assertFalse(result.success());
    }

    @Test
    void selfSignedRejectedByDefault(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-chain", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, null, null, 0, false, false)).get();

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("not trusted") || result.errorMessage().contains("chain"),
                () -> "Error should mention trust/chain: " + result.errorMessage());
    }

    @Test
    void connectionRefused(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-refused", new X509CheckConfiguration(
                "localhost", 19876, "PT5S", null,
                null, null, null, null, 0, true, false)).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void metadataPopulated(@Fusion final X509Check x509Check) throws Exception {
        final var result = x509Check.check(Runnable::run, "test-meta", new X509CheckConfiguration(
                "localhost", port, "PT10S", null,
                null, null, null, null, 0, true, false)).get();

        assertTrue(result.success());
        assertNotNull(result.metadata().get("subject"));
        assertNotNull(result.metadata().get("issuer"));
        assertNotNull(result.metadata().get("serial"));
        assertNotNull(result.metadata().get("validFrom"));
        assertNotNull(result.metadata().get("validUntil"));
        assertNotNull(result.metadata().get("fingerprint"));
        assertNotNull(result.metadata().get("daysUntilExpiration"));
        assertNotNull(result.metadata().get("sans"));
        assertEquals("localhost:" + port, result.metadata().get("target"));
    }
}
