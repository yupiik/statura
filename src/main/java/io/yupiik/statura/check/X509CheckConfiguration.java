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

import java.util.List;

public record X509CheckConfiguration(
        @Property(documentation = "Hostname to connect to.")
        String host,

        @Property(documentation = "Port to connect to.", defaultValue = "443")
        int port,

        @Property(documentation = "Custom (java duration formatted) timeout for this check.", defaultValue = "\"PT30S\"")
        String timeout,

        @Property(documentation = "SNI hostname (defaults to `host`).")
        String sni,

        @Property(value = "expected-subject", documentation = "Expected Subject DN regex pattern.")
        String expectedSubject,

        @Property(value = "expected-issuer", documentation = "Expected Issuer DN regex pattern.")
        String expectedIssuer,

        @Property(value = "expected-fingerprint", documentation = "Expected SHA-256 fingerprint (hex, colon-separated or continuous).")
        String expectedFingerprint,

        @Property(value = "expected-sans", documentation = "Expected Subject Alternative Names.")
        List<String> expectedSans,

        @Property(value = "min-days-until-expiration", documentation = "Minimum days before certificate expiration.", defaultValue = "0")
        int minDaysUntilExpiration,

        @Property(value = "allow-self-signed", documentation = "Whether to accept self-signed certificates.", defaultValue = "false")
        boolean allowSelfSigned,

        @Property(value = "verify-hostname", documentation = "Verify hostname matches certificate SAN/CN.", defaultValue = "true")
        boolean verifyHostname
) {
}
