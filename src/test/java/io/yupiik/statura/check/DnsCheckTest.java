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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@FusionSupport
@TestInstance(PER_CLASS)
class DnsCheckTest {
    @Test
    void successfulResolution(@Fusion final DnsCheck dnsCheck) throws Exception {
        final var result = dnsCheck.check(Runnable::run, "test-dns-ok", new DnsCheckConfiguration("localhost", "PT5S", null)).get();

        assertTrue(result.success());
        assertEquals("test-dns-ok", result.name());
        assertNull(result.errorMessage());
        assertNotNull(result.metadata().get("resolvedAddresses"));
    }

    @Test
    void resolutionWithExpectedAddresses(@Fusion final DnsCheck dnsCheck) throws Exception {
        final var result = dnsCheck.check(Runnable::run, "test-dns-expected", new DnsCheckConfiguration(
                "localhost", "PT5S", List.of("127.0.0.1"))).get();

        assertTrue(result.success());
        assertNull(result.errorMessage());
    }

    @Test
    void resolutionWithWrongExpectedAddresses(@Fusion final DnsCheck dnsCheck) throws Exception {
        final var result = dnsCheck.check(Runnable::run, "test-dns-wrong-expected", new DnsCheckConfiguration(
                "localhost", "PT5S", List.of("1.2.3.4"))).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Expected addresses not found"));
    }

    @Test
    void unknownHostname(@Fusion final DnsCheck dnsCheck) throws Exception {
        final var result = dnsCheck.check(Runnable::run, "test-dns-unknown", new DnsCheckConfiguration(
                "this-does-not-exist.example.com.", "PT5S", null)).get();

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }
}
