package io.yupiik.statura.check;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.statura.model.CheckResult;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

@ApplicationScoped
public class DnsCheck {
    private final Clock clock;

    public DnsCheck(final Clock clock) {
        this.clock = clock;
    }

    public CompletableFuture<CheckResult> check(final Executor executor, final String name, final DnsCheckConfiguration check) {
        final var startMillis = clock.millis();
        final var timestampNanos = TimeUnit.MILLISECONDS.toNanos(startMillis);
        final var timeout = Duration.parse(check.timeout());

        final var future = new CompletableFuture<CheckResult>();

        executor.execute(() -> {
            final var metadata = new HashMap<String, String>();
            metadata.put("hostname", check.hostname());
            try {
                final var addresses = InetAddress.getAllByName(check.hostname());
                final var durationMs = clock.millis() - startMillis;

                final var resolvedIps = Stream.of(addresses)
                        .map(InetAddress::getHostAddress)
                        .collect(toSet());
                metadata.put("resolvedAddresses", String.join(", ", resolvedIps));

                if (check.expectedAddresses() != null && !check.expectedAddresses().isEmpty()) {
                    final var expected = new HashSet<>(check.expectedAddresses());
                    if (resolvedIps.containsAll(expected)) {
                        future.complete(new CheckResult(name, Map.copyOf(metadata), durationMs, timestampNanos, true, null));
                    } else {
                        expected.removeAll(resolvedIps);
                        future.complete(new CheckResult(name, Map.copyOf(metadata), durationMs, timestampNanos, false,
                                "Expected addresses not found: " + expected));
                    }
                } else {
                    future.complete(new CheckResult(name, Map.copyOf(metadata), durationMs, timestampNanos, true, null));
                }
            } catch (final Exception e) {
                final var durationMs = clock.millis() - startMillis;
                if (!future.isDone()) {
                    future.complete(new CheckResult(name, Map.copyOf(metadata), durationMs, timestampNanos, false, e.getMessage()));
                }
            }
        });

        // since resolution is sync, handle the timeout this way
        CompletableFuture
                .delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS, executor)
                .execute(() -> {
                    if (!future.isDone()) {
                        future.complete(new CheckResult(name, Map.of("hostname", check.hostname()), timeout.toMillis(), timestampNanos, false, "DNS resolution timed out"));
                    }
                });

        return future;
    }
}
