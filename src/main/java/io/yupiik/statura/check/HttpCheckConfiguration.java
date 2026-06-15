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


import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

public record HttpCheckConfiguration(
        @Property(documentation = "URL to call.")
        String url,

        @Property(documentation = "HTTP protocol version.", defaultValue = "java.net.http.HttpClient.Version.HTTP_1_1")
        HttpClient.Version version,

        @Property(documentation = "HTTP method to use.", defaultValue = "\"GET\"")
        String method,

        @Property(documentation = "Should redirects be followed.", defaultValue = "false")
        boolean followRedirects,

        @Property(documentation = "HTTP headers to set.", defaultValue = "java.util.Map.of()")
        Map<String, String> headers,

        @Property(documentation = "For _write_ methods, the payload to send.", defaultValue = "\"\"")
        String body,

        @Property(documentation = "Custom (java duration formatted) timeout for this check.", defaultValue = "\"PT30S\"")
        String timeout,

        @Property(value = "expected-status", documentation = "Expected response status.", defaultValue = "200")
        int expectedStatus,

        @Property(documentation = "For JSON responses, some validations on the response payload..", defaultValue = "java.util.List.of()")
        List<Assertion> assertions,

        @Property(value = "ssl-certificates", documentation = "Trusted CA certificates in PEM format for custom SSL trust.")
        List<String> sslCertificates,

        @Property(value = "ssl-client-certificate", documentation = "Client certificate in PEM format for mTLS.")
        String sslClientCertificate,

        @Property(value = "ssl-client-private-key", documentation = "Client private key in PEM format (PKCS#8) for mTLS.")
        String sslClientPrivateKey,

        @Property(value = "ssl-trust-all-certificates", documentation = "Enable to trust all certificates (do not enable for production environment.", defaultValue = "false")
        boolean sslTrustAllCertificates
) {
    public record Assertion(
            @Property(value = "json-pointer", documentation = "JSON-Pointer to extract.", defaultValue = "\"/\"")
            String jsonPointer,

            @Property(documentation = "Expected value", defaultValue = "io.yupiik.statura.check.HttpCheckConfiguration.AssertionOperator.EXISTS")
            AssertionOperator operator,

            @Property(value = "expected-value", documentation = "Expected value", defaultValue = "\"true\"")
            String expectedValue) {
    }

    public enum AssertionOperator {
        EXISTS,
        EQUALS,
        GTE,
        LTE,
        GT,
        LT
    }
}
