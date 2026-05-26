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
