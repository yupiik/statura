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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.statura.model.CheckResult;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.util.Locale.ROOT;

@ApplicationScoped
public class X509Check {
    private final Clock clock;

    public X509Check(final Clock clock) {
        this.clock = clock;
    }

    public CompletableFuture<CheckResult> check(final Executor executor, final String name, final X509CheckConfiguration check) {
        final var startMillis = clock.millis();
        final var timestampNanos = TimeUnit.MILLISECONDS.toNanos(startMillis);
        final var target = check.host() + ":" + check.port();
        final var timeout = Duration.parse(check.timeout());
        final var future = new CompletableFuture<CheckResult>();
        final var capturedChain = new X509Certificate[1][];

        executor.execute(() -> {
            SSLSocket sslSocket = null;
            try {
                final var sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
                                capturedChain[0] = chain;
                            }

                            @Override
                            public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
                                capturedChain[0] = chain;
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                }, null);

                final var sniHostname = check.sni() != null ? check.sni() : check.host();

                final var socket = new java.net.Socket();
                try {
                    socket.connect(new InetSocketAddress(check.host(), check.port()), (int) timeout.toMillis());
                    sslSocket = (SSLSocket) sslContext.getSocketFactory()
                            .createSocket(socket, check.host(), check.port(), true);
                    sslSocket.setSoTimeout((int) timeout.toMillis());

                    final var params = sslSocket.getSSLParameters();
                    params.setServerNames(List.of(new SNIHostName(sniHostname)));
                    sslSocket.setSSLParameters(params);

                    sslSocket.startHandshake();
                } finally {
                    closeQuietly(sslSocket);
                    if (sslSocket == null) {
                        closeQuietly(socket);
                    }
                }

                final var certs = capturedChain[0];
                final var durationMs = clock.millis() - startMillis;
                final var metadata = new HashMap<String, String>();
                metadata.put("target", target);

                if (certs == null || certs.length == 0) {
                    future.complete(new CheckResult(name, Map.copyOf(metadata), durationMs, timestampNanos, false,
                            "No certificate received from server"));
                    return;
                }

                final var leaf = certs[0];
                populateMetadata(metadata, leaf, certs);

                var success = true;
                String error = null;

                // 1. Certificate validity period
                for (final var cert : certs) {
                    try {
                        cert.checkValidity(Date.from(clock.instant()));
                    } catch (final CertificateException e) {
                        success = false;
                        error = "Certificate validity check failed for " +
                                cert.getSubjectX500Principal().getName() + ": " + e.getMessage();
                        break;
                    }
                }

                // 2. Trust chain validation
                if (success && !check.allowSelfSigned()) {
                    try {
                        final var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        tmf.init((java.security.KeyStore) null);
                        final var tm = (X509TrustManager) tmf.getTrustManagers()[0];
                        tm.checkServerTrusted(certs, "UNKNOWN");
                    } catch (final CertificateException e) {
                        success = false;
                        error = "Certificate chain not trusted: " + e.getMessage();
                    }
                }

                // 3. Subject match
                if (success && notBlank(check.expectedSubject()) && !Pattern.matches(check.expectedSubject(), leaf.getSubjectX500Principal().getName())) {
                    success = false;
                    error = "Subject '" + leaf.getSubjectX500Principal().getName() +
                            "' does not match expected pattern '" + check.expectedSubject() + "'";
                }

                // 4. Issuer match
                if (success && notBlank(check.expectedIssuer()) && !Pattern.matches(check.expectedIssuer(), leaf.getIssuerX500Principal().getName())) {
                    success = false;
                    error = "Issuer '" + leaf.getIssuerX500Principal().getName() +
                            "' does not match expected pattern '" + check.expectedIssuer() + "'";
                }

                // 5. Fingerprint match
                if (success && notBlank(check.expectedFingerprint())) {
                    final var normalizedExpected = normalizeFingerprint(check.expectedFingerprint());
                    final var actualFingerprint = normalizeFingerprint(sha256Fingerprint(leaf));
                    if (!normalizedExpected.equals(actualFingerprint)) {
                        success = false;
                        error = "Fingerprint does not match expected value";
                    }
                }

                // 6. Expected SANs
                if (success && check.expectedSans() != null && !check.expectedSans().isEmpty()) {
                    final var sansSet = new HashSet<>(extractSans(leaf));
                    for (final var expectedSan : check.expectedSans()) {
                        if (!sansSet.contains(expectedSan)) {
                            success = false;
                            error = "Expected SAN '" + expectedSan + "' not found in certificate";
                            break;
                        }
                    }
                }

                // 7. Min days until expiration
                if (success && check.minDaysUntilExpiration() > 0) {
                    final var minDays = minDaysUntilExpiration(certs);
                    if (minDays < check.minDaysUntilExpiration()) {
                        success = false;
                        error = "Certificate expires in " + minDays + " days, minimum required is " +
                                check.minDaysUntilExpiration();
                    }
                }

                // 8. Hostname verification
                if (success && check.verifyHostname() && !verifyHostname(check.host(), leaf)) {
                    success = false;
                    error = "Hostname '" + check.host() + "' does not match certificate SANs/CN";
                }

                future.complete(new CheckResult(name, Map.copyOf(metadata), durationMs, timestampNanos, success, error));

            } catch (final Exception e) {
                final var durationMs = clock.millis() - startMillis;
                final var metadata = new HashMap<String, String>();
                metadata.put("target", target);
                final var captured = capturedChain[0];
                if (captured != null && captured.length > 0) {
                    populateMetadata(metadata, captured[0], captured);
                }
                future.complete(new CheckResult(name, Map.copyOf(metadata), durationMs, timestampNanos, false, e.getMessage()));
            }
        });

        CompletableFuture
                .delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS, executor)
                .execute(() -> {
                    if (!future.isDone()) {
                        future.complete(new CheckResult(name, Map.of("target", target), timeout.toMillis(),
                                timestampNanos, false, "X509 check timed out"));
                    }
                });

        return future;
    }

    private void populateMetadata(final Map<String, String> metadata, final X509Certificate leaf, final X509Certificate[] chain) {
        try {
            metadata.put("subject", leaf.getSubjectX500Principal().getName());
            metadata.put("issuer", leaf.getIssuerX500Principal().getName());
            metadata.put("serial", leaf.getSerialNumber().toString());
            metadata.put("validFrom", leaf.getNotBefore().toInstant().toString());
            metadata.put("validUntil", leaf.getNotAfter().toInstant().toString());
            metadata.put("fingerprint", sha256Fingerprint(leaf));

            final var sans = extractSans(leaf);
            if (!sans.isEmpty()) {
                metadata.put("sans", String.join(", ", sans));
            }

            metadata.put("daysUntilExpiration", String.valueOf(minDaysUntilExpiration(chain)));
        } catch (final Exception ignored) {
        }
    }

    private long minDaysUntilExpiration(final X509Certificate[] chain) {
        var min = Long.MAX_VALUE;
        for (final var cert : chain) {
            final var days = Duration.between(clock.instant(), cert.getNotAfter().toInstant()).toDays();
            if (days < min) {
                min = days;
            }
        }
        return min;
    }

    private String sha256Fingerprint(final X509Certificate cert) {
        try {
            final var md = MessageDigest.getInstance("SHA-256");
            final var digest = md.digest(cert.getEncoded());
            final var sb = new StringBuilder(digest.length * 3 - 1);
            for (final var b : digest) {
                if (!sb.isEmpty()) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (final Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String normalizeFingerprint(final String fp) {
        return fp.replace(":", "").replace("-", "").toLowerCase(ROOT);
    }

    private List<String> extractSans(final X509Certificate cert) {
        try {
            final var sans = cert.getSubjectAlternativeNames();
            if (sans == null) {
                return List.of();
            }
            return sans.stream().map(entry -> {
                final var type = (Integer) entry.get(0);
                final var value = entry.get(1).toString();
                return switch (type) {
                    case 0 -> "otherName:" + value;
                    case 1 -> "rfc822:" + value;
                    case 2 -> "dns:" + value;
                    case 3 -> "x400Address:" + value;
                    case 4 -> "directoryName:" + value;
                    case 5 -> "ediPartyName:" + value;
                    case 6 -> "uri:" + value;
                    case 7 -> "ip:" + value;
                    case 8 -> "registeredId:" + value;
                    default -> "type" + type + ":" + value;
                };
            }).toList();
        } catch (final CertificateParsingException e) {
            return List.of();
        }
    }

    private boolean verifyHostname(final String hostname, final X509Certificate cert) {
        try {
            final var sans = cert.getSubjectAlternativeNames();
            if (sans != null && !sans.isEmpty()) {
                final var dnsNames = new ArrayList<String>();
                for (final var san : sans) {
                    final var type = (Integer) san.get(0);
                    if (type == 2) {
                        dnsNames.add(san.get(1).toString().toLowerCase(ROOT));
                    }
                }
                if (!dnsNames.isEmpty()) {
                    return matchHostname(hostname, dnsNames);
                }
            }
        } catch (final CertificateParsingException ignored) {
            // no-op
        }

        final var subject = cert.getSubjectX500Principal().getName();
        for (final var part : subject.split(",")) {
            final var trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                return matchHostname(hostname, List.of(trimmed.substring(3).toLowerCase(ROOT)));
            }
        }

        return false;
    }

    private boolean matchHostname(final String hostname, final List<String> patterns) {
        final var lower = hostname.toLowerCase(ROOT);
        for (final var pattern : patterns) {
            if (pattern.equals(lower)) {
                return true;
            }
            if (pattern.startsWith("*.")) {
                final var suffix = pattern.substring(1);
                if (lower.endsWith(suffix) && lower.indexOf('.') == lower.length() - suffix.length()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean notBlank(final String s) {
        return s != null && !s.isBlank();
    }

    private void closeQuietly(final java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (final Exception ignored) {
                // no-op
            }
        }
    }
}
