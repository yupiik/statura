package io.yupiik.statura.ssl;

import java.util.List;

public record SslConfiguration(
        List<String> sslCertificates,
        String sslClientCertificate,
        String sslClientPrivateKey
) {
    public boolean isEmpty() {
        return (sslCertificates == null || sslCertificates.isEmpty()) &&
                (sslClientCertificate == null || sslClientCertificate.isBlank()) &&
                (sslClientPrivateKey == null || sslClientPrivateKey.isBlank());
    }
}
