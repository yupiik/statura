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
package io.yupiik.statura.ssl;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class SslContextService {

    public SSLContext buildSslContext(final SslConfiguration config) {
        if (config.isEmpty()) {
            return null;
        }

        final var hasTrustedCerts = config.sslCertificates() != null && !config.sslCertificates().isEmpty();
        final var hasClientCert = config.sslClientCertificate() != null && !config.sslClientCertificate().isBlank();
        final var hasClientKey = config.sslClientPrivateKey() != null && !config.sslClientPrivateKey().isBlank();

        try {
            if (hasClientCert && !hasClientKey) {
                throw new IllegalArgumentException("ssl-client-private-key is required when ssl-client-certificate is set");
            }
            if (hasClientKey && !hasClientCert) {
                throw new IllegalArgumentException("ssl-client-certificate is required when ssl-client-private-key is set");
            }

            final var trustStore = hasTrustedCerts ? buildTrustStore(config.sslCertificates()) : null;
            final var keyStore = hasClientCert ? buildKeyStore(config.sslClientCertificate(), config.sslClientPrivateKey()) : null;

            final var tmf = trustStore != null ? buildTrustManagerFactory(trustStore) : null;
            final var kmf = keyStore != null ? buildKeyManagerFactory(keyStore) : null;

            final var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    kmf != null ? kmf.getKeyManagers() : null,
                    tmf != null ? tmf.getTrustManagers() : null,
                    null);
            return sslContext;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to build SSL context: " + e.getMessage(), e);
        }
    }

    private KeyStore buildTrustStore(final List<String> pemCertificates) throws Exception {
        final var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        final var certFactory = CertificateFactory.getInstance("X.509");
        var idx = 0;
        for (final var pem : pemCertificates) {
            try (var in = new ByteArrayInputStream(pem.getBytes(UTF_8))) {
                for (final var cert : certFactory.generateCertificates(in)) {
                    trustStore.setCertificateEntry("trusted-" + (idx++), cert);
                }
            }
        }
        return trustStore;
    }

    private KeyStore buildKeyStore(final String clientCertPem, final String clientKeyPem) throws Exception {
        final var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);

        final var certFactory = CertificateFactory.getInstance("X.509");

        try (var in = new ByteArrayInputStream(clientCertPem.getBytes(UTF_8))) {
            final var certs = certFactory.generateCertificates(in).stream()
                    .map(X509Certificate.class::cast)
                    .toList();
            keyStore.setKeyEntry("client", parsePrivateKey(clientKeyPem), new char[0], certs.toArray(X509Certificate[]::new));
        }
        return keyStore;
    }

    private PrivateKey parsePrivateKey(final String pem) {
        final var base64 = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        final var decoded = Base64.getDecoder().decode(base64);
        KeyFactory rsa = null;
        try {
            rsa = KeyFactory.getInstance("RSA");
            return rsa.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (final Exception e) {
            if (rsa == null) {
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
            try {
                return rsa.generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1(decoded)));
            } catch (final Exception e2) {
                throw new IllegalArgumentException("Unsupported private key format (expected PKCS#8 PEM)", e2);
            }
        }
    }

    private byte[] wrapPkcs1(final byte[] pkcs1) {
        final var algorithmId = new byte[]{
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        final var wrappedKey = encodeOctetString(pkcs1);
        return encodeSequence(encodeSequence(encodeInteger(0), algorithmId, wrappedKey));
    }

    private byte[] encodeInteger(final int value) {
        if (value < 0x80) {
            return new byte[]{0x02, (byte) value};
        }
        return new byte[]{0x02, 0x01, (byte) value};
    }

    private byte[] encodeOctetString(final byte[] data) {
        return encodeTag(0x04, data);
    }

    private byte[] encodeSequence(final byte[]... parts) {
        var totalLen = 0;
        for (final var p : parts) totalLen += p.length;
        final var content = new byte[totalLen];
        var pos = 0;
        for (final var p : parts) {
            System.arraycopy(p, 0, content, pos, p.length);
            pos += p.length;
        }
        return encodeTag(0x30, content);
    }

    private byte[] encodeTag(final int tag, final byte[] content) {
        final var lenBytes = encodeLength(content.length);
        final var result = new byte[1 + lenBytes.length + content.length];
        result[0] = (byte) tag;
        System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
        System.arraycopy(content, 0, result, 1 + lenBytes.length, content.length);
        return result;
    }

    private byte[] encodeLength(final int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        final var bytes = new byte[4];
        var len = length;
        var count = 0;
        while (len > 0) {
            bytes[count++] = (byte) (len & 0xFF);
            len >>>= 8;
        }
        final var result = new byte[1 + count];
        result[0] = (byte) (0x80 | count);
        for (var i = 0; i < count; i++) {
            result[1 + i] = bytes[count - 1 - i];
        }
        return result;
    }

    private KeyManagerFactory buildKeyManagerFactory(final KeyStore keyStore) throws Exception {
        final var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);
        return kmf;
    }

    private TrustManagerFactory buildTrustManagerFactory(final KeyStore trustStore) throws Exception {
        final var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }
}
