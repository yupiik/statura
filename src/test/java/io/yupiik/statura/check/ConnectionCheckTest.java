package io.yupiik.statura.check;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@FusionSupport
@TestInstance(PER_CLASS)
class ConnectionCheckTest {
    private ServerSocket server;
    private int port;

    @BeforeAll
    void setUp() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress("localhost", 0));
        port = server.getLocalPort();
    }

    @AfterAll
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void successfulConnection(@Fusion final ConnectionCheck connectionCheck) throws Exception {
        final var result = connectionCheck.check("test-conn-ok", new ConnectionCheckConfiguration(
                "localhost", port, "PT5S", null), Runnable::run).get();

        assertTrue(result.success());
        assertEquals("test-conn-ok", result.name());
        assertNull(result.errorMessage());
    }

    @Test
    void connectionRefused(@Fusion final ConnectionCheck connectionCheck) throws Exception {
        final var badPort = 19876;
        final var result = connectionCheck.check("test-conn-refused", new ConnectionCheckConfiguration(
                "localhost", badPort, "PT5S", null), Runnable::run).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }
}
