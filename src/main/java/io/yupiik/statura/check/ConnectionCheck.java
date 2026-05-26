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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ConnectionCheck {
    private final Clock clock;

    public ConnectionCheck(final Clock clock) {
        this.clock = clock;
    }

    public CompletableFuture<CheckResult> check(final String name, final ConnectionCheckConfiguration check, final Executor executor) {
        final var startMillis = clock.millis();
        final var timestampNanos = TimeUnit.MILLISECONDS.toNanos(startMillis);
        final var target = check.host() + ":" + check.port();
        final var timeout = Duration.parse(check.timeout());

        final var metadata = Map.of("url", target);
        try {
            final var channel = AsynchronousSocketChannel.open();
            final var future = new CompletableFuture<CheckResult>();

            final var proxy = check.proxy();
            final var connectFuture = (proxy != null && proxy.type() != null && !"DIRECT".equals(proxy.type()))
                    ? proxyConnect(channel, proxy, check.host(), check.port())
                    : connectDirect(channel, check.host(), check.port());

            connectFuture.whenComplete((_, t) -> {
                final var durationMs = clock.millis() - startMillis;
                if (t != null) {
                    future.complete(new CheckResult(name, metadata, durationMs, timestampNanos, false, t.getMessage()));
                } else {
                    future.complete(new CheckResult(name, metadata, durationMs, timestampNanos, true, null));
                }
            });

            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS, executor)
                    .execute(() -> {
                        if (!future.isDone()) {
                            close(channel);
                            future.complete(new CheckResult(name, metadata, timeout.toMillis(), timestampNanos, false, "Connection timed out"));
                        }
                    });

            future.whenComplete((r, t) -> close(channel));

            return future;
        } catch (final IOException e) {
            final var durationMs = clock.millis() - startMillis;
            return CompletableFuture.completedFuture(
                    new CheckResult(name, metadata, durationMs, timestampNanos, false, e.getMessage()));
        }
    }

    private CompletableFuture<Void> connectDirect(final AsynchronousSocketChannel channel, final String host, final int port) {
        final var future = new CompletableFuture<Void>();
        channel.connect(new InetSocketAddress(host, port), null, new CompletionHandler<Void, Void>() {
            @Override
            public void completed(final Void result, final Void attachment) {
                future.complete(null);
            }

            @Override
            public void failed(final Throwable exc, final Void attachment) {
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    private CompletableFuture<Void> proxyConnect(
            final AsynchronousSocketChannel channel,
            final ConnectionCheckConfiguration.Proxy proxy,
            final String targetHost, final int targetPort) {
        return connectDirect(channel, proxy.host(), proxy.port())
                .thenCompose(v -> doProxyHandshake(channel, proxy.type(), targetHost, targetPort));
    }

    private CompletableFuture<Void> doProxyHandshake(
            final AsynchronousSocketChannel channel, final String proxyType,
            final String targetHost, final int targetPort) {
        return switch (proxyType) {
            case "SOCKS", "SOCKS5" -> socks5Connect(channel, targetHost, targetPort);
            case "SOCKS4", "SOCKS4a" -> socks4Connect(channel, targetHost, targetPort);
            case "HTTP" -> httpConnect(channel, targetHost, targetPort);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported proxy type: " + proxyType));
        };
    }

    private CompletableFuture<Void> socks5Connect(final AsynchronousSocketChannel channel, final String host, final int port) {
        final var hostBytes = host.getBytes(StandardCharsets.UTF_8);

        return write(channel, new byte[]{0x05, 0x01, 0x00})
                .thenCompose(v -> read(channel, 2))
                .thenCompose(response -> {
                    if (response[0] != 0x05 || response[1] != 0x00) {
                        return CompletableFuture.failedFuture(new IOException("SOCKS5 handshake rejected"));
                    }
                    final var buf = ByteBuffer.allocate(4 + 1 + hostBytes.length + 2);
                    buf.put(new byte[]{0x05, 0x01, 0x00, 0x03});
                    buf.put((byte) hostBytes.length);
                    buf.put(hostBytes);
                    buf.putShort((short) port);
                    buf.flip();
                    final var data = new byte[buf.remaining()];
                    buf.get(data);
                    return write(channel, data);
                })
                .thenCompose(v -> read(channel, 4))
                .thenCompose(header -> {
                    if (header[0] != 0x05 || header[1] != 0x00) {
                        return CompletableFuture.failedFuture(new IOException("SOCKS5 connect failed: " + header[1]));
                    }
                    return readSocks5Address(channel, header[3]);
                });
    }

    private CompletableFuture<Void> readSocks5Address(final AsynchronousSocketChannel channel, final int atyp) {
        return switch (atyp) {
            case 0x01 -> read(channel, 4 + 2).thenApply(__ -> null);
            case 0x03 -> read(channel, 1).thenCompose(len -> read(channel, (len[0] & 0xFF) + 2)).thenApply(__ -> null);
            case 0x04 -> read(channel, 16 + 2).thenApply(__ -> null);
            default -> CompletableFuture.failedFuture(new IOException("Unknown SOCKS5 address type: " + atyp));
        };
    }

    private CompletableFuture<Void> socks4Connect(final AsynchronousSocketChannel channel, final String host, final int port) {
        final var hostBytes = host.getBytes(StandardCharsets.UTF_8);
        final var buf = ByteBuffer.allocate(8 + 1 + hostBytes.length + 1);
        buf.put(new byte[]{0x04, 0x01});
        buf.putShort((short) port);
        buf.put(new byte[]{0x00, 0x00, 0x00, 0x01});
        buf.put((byte) 0x00);
        buf.put(hostBytes);
        buf.put((byte) 0x00);
        buf.flip();
        final var data = new byte[buf.remaining()];
        buf.get(data);

        return write(channel, data)
                .thenCompose(v -> read(channel, 8))
                .thenCompose(response -> {
                    if (response[1] != 0x5A) {
                        return CompletableFuture.failedFuture(new IOException("SOCKS4 connect denied, status: " + (response[1] & 0xFF)));
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    private CompletableFuture<Void> httpConnect(final AsynchronousSocketChannel channel, final String host, final int port) {
        final var request = ("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        return write(channel, request)
                .thenCompose(v -> read(channel, 8192))
                .thenCompose(response -> {
                    final var responseStr = new String(response, StandardCharsets.UTF_8);
                    if (!responseStr.contains("200")) {
                        final var statusLine = responseStr.split("\r\n")[0];
                        return CompletableFuture.failedFuture(new IOException("HTTP proxy CONNECT failed: " + statusLine));
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    private CompletableFuture<Void> write(final AsynchronousSocketChannel channel, final byte[] data) {
        final var future = new CompletableFuture<Void>();
        channel.write(ByteBuffer.wrap(data), null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(final Integer result, final Void attachment) {
                future.complete(null);
            }

            @Override
            public void failed(final Throwable exc, final Void attachment) {
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    private CompletableFuture<byte[]> read(final AsynchronousSocketChannel channel, final int len) {
        final var future = new CompletableFuture<byte[]>();
        final var buf = ByteBuffer.allocate(len);
        channel.read(buf, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(final Integer result, final Void attachment) {
                if (result == -1) {
                    future.completeExceptionally(new IOException("Connection closed"));
                } else {
                    buf.flip();
                    final var data = new byte[buf.remaining()];
                    buf.get(data);
                    future.complete(data);
                }
            }

            @Override
            public void failed(final Throwable exc, final Void attachment) {
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    private void close(final AsynchronousSocketChannel channel) {
        if (channel.isOpen()) {
            try {
                channel.close();
            } catch (final IOException e) {
                // no-op
            }
        }
    }
}
