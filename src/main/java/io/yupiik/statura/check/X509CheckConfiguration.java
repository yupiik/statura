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
