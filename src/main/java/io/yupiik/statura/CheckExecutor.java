package io.yupiik.statura;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.statura.check.HttpCheck;
import io.yupiik.statura.check.HttpCheckConfiguration;
import io.yupiik.statura.model.CheckResult;
import io.yupiik.statura.otel.OpenTelemetry;
import io.yupiik.statura.otel.OtlpMetricsFlusher;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@DefaultScoped
@Command(name = "executor", description = "Run synthetic checks against configured URLs and push OTEL metrics.")
public class CheckExecutor implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final CheckExecutorConfiguration conf;
    private final HttpCheck httpCheck;
    private final OtlpMetricsFlusher flusher;

    public CheckExecutor(final CheckExecutorConfiguration configuration,
                         final HttpCheck httpCheck,
                         final OtlpMetricsFlusher flusher) {
        this.conf = configuration;
        this.httpCheck = httpCheck;
        this.flusher = flusher;
    }

    @Override
    public void run() {
        if (conf.checks().isEmpty()) {
            logger.info("No checks defined, exiting.");
            return;
        }
        logger.info(() -> "Found " + conf.checks().size() + " checks");

        try (final var client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                .setRequestListeners(List.of())
                .setDelegate(HttpClient.newBuilder()
                        .connectTimeout(Duration.parse(conf.defaultTimeout()))
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build()))) {
            final var results = runChecks(client);
            logger.info(() -> {
                final var ok = results.stream().filter(CheckResult::success).count();
                return "Completed " + results.size() + " checks (" + ok + " ok, " + (results.size() - ok) + " ko)";
            });

            flusher.flush(conf.opentelemetry(), results);
            logger.info(() -> "Metrics pushed to '" + conf.opentelemetry().endpoint() + "'");
        }
    }

    private List<CheckResult> runChecks(final HttpClient client) {
        final var checks = conf.checks().stream()
                .map(it -> getRunHttpCheck(client, it))
                .toList();
        final var timeout = Duration.parse(conf.executionTimeout());
        try {
            return allOf(checks.toArray(CompletableFuture[]::new))
                    .thenApply(_ -> checks.stream().map(f -> f.getNow(null)).toList())
                    .get(timeout.toMillis(), MILLISECONDS);
        } catch (final InterruptedException e) {
            throw new IllegalStateException("Execution was cancelled", e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException("Unexpected execution", e.getCause());
        } catch (final TimeoutException e) {
            throw new IllegalStateException("Execution lasted more than the global timeout", e);
        }
    }

    private CompletableFuture<CheckResult> getRunHttpCheck(final HttpClient client, final CheckConfig it) {
        return switch (it.type()) {
            case HTTP -> httpCheck.check(client, it.name(), it.http());
        };
    }

    @RootConfiguration("executor")
    public record CheckExecutorConfiguration(
            @Property(documentation = "List of validations to do.", defaultValue = "java.util.List.of()")
            List<CheckConfig> checks,

            @Property(documentation = "OpenTelemetry configuration.")
            OpenTelemetry opentelemetry,

            @Property(value = "execution-timeout", documentation = "Default timeout for the whole execution.", defaultValue = "\"PT15M\"")
            String executionTimeout,

            @Property(value = "default-timeout", documentation = "Default timeout per check.", defaultValue = "\"PT30S\"")
            String defaultTimeout,

            @Property(value = "max-concurrent", documentation = "Max parallel checks.", defaultValue = "50")
            int maxConcurrent
    ) {
    }

    public enum CheckType {
        HTTP
    }

    public record CheckConfig(
            @Property(documentation = "Check name (or an internal one is generated).")
            String name,

            @Property(documentation = "Type of the validation.", defaultValue = "io.yupiik.statura.CheckExecutor.CheckType.HTTP")
            CheckType type,

            @Property(documentation = "When type==HTTP, the configuration of the check to do.")
            HttpCheckConfiguration http
    ) {
    }
}
