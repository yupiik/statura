package io.yupiik.statura.check;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import io.yupiik.statura.model.CheckResult;
import io.yupiik.statura.ssl.SslConfiguration;
import io.yupiik.statura.ssl.SslContextService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static java.net.http.HttpResponse.BodyHandlers.ofString;

@ApplicationScoped
public class HttpCheck {
    private final JsonMapper mapper;
    private final Clock clock;
    private final SslContextService sslContextService;

    public HttpCheck(final JsonMapper mapper,
                     final Clock clock,
                     final SslContextService sslContextService) {
        this.mapper = mapper;
        this.clock = clock;
        this.sslContextService = sslContextService;
    }

    public CompletableFuture<CheckResult> check(final List<RequestListener<?>> listeners,
                                                final Executor pipelineExecutor,
                                                final Duration timeout,
                                                final String name,
                                                final HttpCheckConfiguration check) {
        final var request = buildRequest(check);
        final var startMillis = clock.millis();
        final var timestampNanos = TimeUnit.MILLISECONDS.toNanos(startMillis);

        final var httpBuilder = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .executor(pipelineExecutor)
                .followRedirects(check.followRedirects() ? HttpClient.Redirect.ALWAYS : HttpClient.Redirect.NEVER);

        final var sslContext = sslContextService.buildSslContext(new SslConfiguration(
                check.sslCertificates(), check.sslClientCertificate(), check.sslClientPrivateKey()));
        if (sslContext != null) {
            httpBuilder.sslContext(sslContext);
        }

        final var delegate = httpBuilder.build();
        final var client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                .setRequestListeners(listeners)
                .setDelegate(delegate));
        try {
            return client
                    .sendAsync(request, ofString())
                    .thenApplyAsync(response -> evaluate(name, check, response, timestampNanos), pipelineExecutor)
                    .exceptionallyAsync(ex -> new CheckResult(
                            name, Map.of("http.route", check.url(), "http.request.method", request.method()),
                            clock.millis() - timestampNanos,
                            timestampNanos, false, ex.getMessage()), pipelineExecutor)
                    .thenApply(it -> {
                        client.close();
                        return it;
                    });
        } catch (final RuntimeException re) {
            client.close();
            throw re;
        }
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
        final var now = clock.millis();
        final var durationMs = now - timestampNanos;
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

        return new CheckResult(
                name,
                Map.of("http.route", check.url(), "http.request.method", response.request().method(), "http.response.status_code", String.valueOf(status)),
                durationMs, timestampNanos, success, error);
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
