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

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@FusionSupport
@TestInstance(PER_CLASS)
class JdbcCheckTest {
    private String jdbcUrl;

    @BeforeAll
    void setUp() {
        jdbcUrl = "jdbc:h2:mem:JdbcCheckTest;DB_CLOSE_DELAY=-1";
    }

    @AfterAll
    void tearDown() throws SQLException {
        try (final var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             final var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void successfulJdbcCheck(@Fusion final JdbcCheck jdbcCheck) throws Exception {
        final var result = jdbcCheck.check("test-jdbc-ok", new JdbcCheckConfiguration(
                jdbcUrl, "", "sa", "", "SELECT 1", "PT10S"), () -> Runnable::run).get();

        assertTrue(result.success());
        assertEquals("test-jdbc-ok", result.name());
        assertNull(result.errorMessage());
    }

    @Test
    void jdbcConnectionFailure(@Fusion final JdbcCheck jdbcCheck) throws Exception {
        final var result = jdbcCheck.check("test-jdbc-fail", new JdbcCheckConfiguration(
                "jdbc:h2:tcp://localhost:1/nope", null, "sa", "", "SELECT 1", "PT5S"), () -> Runnable::run).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void jdbcInvalidQuery(@Fusion final JdbcCheck jdbcCheck) throws Exception {
        final var result = jdbcCheck.check("test-jdbc-bad-query", new JdbcCheckConfiguration(
                jdbcUrl, "", "sa", "", "SELECT invalid", "PT10S"), () -> Runnable::run).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }
}
