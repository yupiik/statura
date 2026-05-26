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

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.util.concurrent.CompletableFuture.completedFuture;

@ApplicationScoped
public class JdbcCheck {
    private final Clock clock;

    public JdbcCheck(final Clock clock) {
        this.clock = clock;
    }

    public CompletableFuture<CheckResult> check(final String name, final JdbcCheckConfiguration check, final Supplier<Executor> executorSupplier) {
        final var metadata = Map.of("url", check.url());
        final var startMillis = clock.millis();
        final var timestampNanos = TimeUnit.MILLISECONDS.toNanos(startMillis);
        try {
            final var props = new Properties();
            if (check.username() != null && !check.username().isBlank()) {
                props.setProperty("user", check.username());
                props.setProperty("password", check.password() == null ? "" : check.password());
            }

            if (check.driver() != null && !check.driver().isBlank()) {
                Class.forName(check.driver().strip(), true, Thread.currentThread().getContextClassLoader());
            }

            final var res = new CompletableFuture<CheckResult>();
            executorSupplier.get().execute(() -> {
                try (final var connection = DriverManager.getConnection(check.url(), props)) {
                    if (check.validationQuery() == null || check.validationQuery().isBlank()) {
                        if (!connection.isValid((int) Duration.parse(check.timeout()).toSeconds())) {
                            throw new IllegalStateException("Connection not valid in " + check.timeout());
                        }
                    } else {
                        try (final var statement = connection.createStatement()) {
                            statement.execute(check.validationQuery());
                        }
                    }
                } catch (final Exception e) {
                    final var durationMs = clock.millis() - startMillis;
                    res.complete(new CheckResult(name, metadata, durationMs, timestampNanos, false, e.getMessage()));
                    return;
                }

                final var durationMs = clock.millis() - startMillis;
                res.complete(new CheckResult(name, metadata, durationMs, timestampNanos, true, null));
            });
            return res;
        } catch (final Exception e) {
            final var durationMs = clock.millis() - startMillis;
            return completedFuture(new CheckResult(name, metadata, durationMs, timestampNanos, false, e.getMessage()));
        }
    }
}
