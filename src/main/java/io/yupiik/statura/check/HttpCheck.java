package io.yupiik.statura.check;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import io.yupiik.statura.model.CheckResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.net.http.HttpResponse.BodyHandlers.ofString;

@ApplicationScoped
public class HttpCheck {
    private final JsonMapper mapper;
    private final Clock clock;

    public HttpCheck(final JsonMapper mapper, final Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public CompletableFuture<CheckResult> check(final HttpClient client, final String name, final HttpCheckConfiguration check) {
        final var startMillis = clock.millis();
        final var timestampNanos = TimeUnit.MILLISECONDS.toNanos(startMillis);
        final var request = buildRequest(check);
        return client
                .sendAsync(request, ofString())
                .thenApplyAsync(response -> evaluate(name, check, response, timestampNanos), client.executor().orElseThrow())
                .exceptionally(ex -> new CheckResult(
                        name, Map.of("url", check.url()),
                        clock.millis() - timestampNanos,
                        timestampNanos, false, ex.getMessage()));
    }

    private HttpRequest buildRequest(final HttpCheckConfiguration check) {
        final var method = check.method() != null ? check.method().toUpperCase() : "GET";
        final var bodyPublisher = check.body() != null && !check.body().isBlank() ? HttpRequest.BodyPublishers.ofString(check.body()) : HttpRequest.BodyPublishers.noBody();
        final var base = HttpRequest.newBuilder()
                .uri(URI.create(check.url()))
                .timeout(Duration.parse(check.timeout()))
                .method(method, bodyPublisher)
                .version(check.version());
        check.headers().forEach(base::header);
        return base.build();
    }

    private CheckResult evaluate(final String name,
                                 final HttpCheckConfiguration check,
                                 final HttpResponse<String> response,
                                 final long timestampNanos) {
        final var durationMs = clock.millis() - timestampNanos;
        final var status = response.statusCode();
        final var body = response.body();

        var success = status == (check.expectedStatus() > 0 ? check.expectedStatus() : 200);
        String error = null;

        if (success && check.assertions() != null && !check.assertions().isEmpty()) {
            try {
                final var parsed = mapper.fromString(Object.class, body);
                for (final var assertion : check.assertions()) {
                    final var pointer = new GenericJsonPointer(assertion.jsonPointer());
                    final var actual = pointer.apply(parsed);
                    if (!matches(assertion, actual)) {
                        success = false;
                        error = "Assertion failed: " + assertion.jsonPointer() + " " +
                                assertion.operator() + " '" + assertion.expectedValue() +
                                "' but got '" + actual + "'";
                        break;
                    }
                }
            } catch (final Exception e) {
                success = false;
                error = "Assertion error: " + e.getMessage();
            }
        }

        return new CheckResult(name, Map.of("url", check.url(), "status", String.valueOf(status)), durationMs, timestampNanos, success, error);
    }

    private boolean matches(final HttpCheckConfiguration.Assertion assertion, final Object actual) {
        return switch (assertion.operator()) {
            case EXISTS -> actual != null;
            case EQUALS -> actual != null && assertion.expectedValue().equals(actual.toString());
            case GTE, LTE, GT, LT -> {
                if (!(actual instanceof Number actualNum)) {
                    yield false;
                }
                final var expectedNum = Double.parseDouble(assertion.expectedValue());
                yield switch (assertion.operator()) {
                    case GTE -> actualNum.doubleValue() >= expectedNum;
                    case LTE -> actualNum.doubleValue() <= expectedNum;
                    case GT -> actualNum.doubleValue() > expectedNum;
                    case LT -> actualNum.doubleValue() < expectedNum;
                    default -> false;
                };
            }
        };
    }
}
