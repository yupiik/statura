package io.yupiik.statura;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import io.yupiik.statura.check.ConnectionCheck;
import io.yupiik.statura.check.ConnectionCheckConfiguration;
import io.yupiik.statura.check.DnsCheck;
import io.yupiik.statura.check.DnsCheckConfiguration;
import io.yupiik.statura.check.HttpCheck;
import io.yupiik.statura.check.HttpCheckConfiguration;
import io.yupiik.statura.check.JdbcCheck;
import io.yupiik.statura.check.JdbcCheckConfiguration;
import io.yupiik.statura.check.X509Check;
import io.yupiik.statura.check.X509CheckConfiguration;
import io.yupiik.statura.model.CheckResult;
import io.yupiik.statura.otel.OpenTelemetry;
import io.yupiik.statura.otel.OtlpMetricsFlusher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static io.yupiik.statura.CheckExecutor.CheckType.JDBC;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@DefaultScoped
@Command(name = "executor", description = "Run synthetic checks against configured URLs and push OTEL metrics.")
public class CheckExecutor implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final CheckExecutorConfiguration conf;
    private final HttpCheck httpCheck;
    private final ConnectionCheck connectionCheck;
    private final JdbcCheck jdbcCheck;
    private final DnsCheck dnsCheck;
    private final X509Check x509Check;
    private final OtlpMetricsFlusher flusher;

    public CheckExecutor(final CheckExecutorConfiguration configuration,
                         final HttpCheck httpCheck,
                         final ConnectionCheck connectionCheck,
                         final JdbcCheck jdbcCheck,
                         final DnsCheck dnsCheck,
                         final X509Check x509Check,
                         final OtlpMetricsFlusher flusher) {
        this.conf = configuration;
        this.httpCheck = httpCheck;
        this.connectionCheck = connectionCheck;
        this.jdbcCheck = jdbcCheck;
        this.dnsCheck = dnsCheck;
        this.x509Check = x509Check;
        this.flusher = flusher;
    }

    @Override
    public void run() {
        if (conf.checks().isEmpty()) {
            logger.info("No checks defined, exiting.");
            return;
        }

        logger.info(() -> "Found " + conf.checks().size() + " checks");

        final var timeout = Duration.parse(conf.defaultTimeout());
        final var listeners = new ArrayList<RequestListener<?>>();
        try (final var defaultExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var results = runChecks(listeners, defaultExecutor, timeout);
            logger.info(() -> {
                final var ok = results.stream().filter(CheckResult::success).count();
                return "Completed " + results.size() + " checks (" + ok + " ok, " + (results.size() - ok) + " ko)";
            });

            flusher.flush(conf.opentelemetry(), results);
            logger.info(() -> "Metrics pushed to '" + conf.opentelemetry().endpoint() + "'");
        }
    }

    private List<CheckResult> runChecks(final List<RequestListener<?>> client, final Executor defaultExecutor, final Duration timeout) {
        final var executors = new ConcurrentHashMap<CheckType, LazyExecutor>();
        final var semaphores = new ConcurrentHashMap<CheckType, Semaphore>();
        final var futures = conf.checks().stream()
                .map(it -> runThrottled(client, defaultExecutor, timeout, executors, semaphores, it))
                .toList();
        final var globalTimeout = Duration.parse(conf.executionTimeout());
        try {
            return allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(_ -> futures.stream().map(f -> f.getNow(null)).toList())
                    .get(globalTimeout.toMillis(), MILLISECONDS);
        } catch (final InterruptedException e) {
            throw new IllegalStateException("Execution was cancelled", e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException("Unexpected execution", e.getCause());
        } catch (final TimeoutException e) {
            throw new IllegalStateException("Execution lasted more than the global timeout", e);
        } finally {
            executors.values().stream()
                    .map(it -> it.delegate)
                    .filter(ExecutorService.class::isInstance)
                    .map(ExecutorService.class::cast)
                    .forEach(ExecutorService::close);
        }
    }

    private CompletableFuture<CheckResult> runThrottled(
            final List<RequestListener<?>> client,
            final Executor pipelineExecutor, final Duration timeout,
            final Map<CheckType, LazyExecutor> executors,
            final Map<CheckType, Semaphore> semaphores,
            final CheckConfig check) {
        final var max = Integer.parseInt(conf.maxConcurrentPerType().getOrDefault(check.type().name(), "0"));
        if (max <= 0) {
            return doCheck(client, check, pipelineExecutor, timeout, executors);
        }

        final var semaphore = semaphores.computeIfAbsent(check.type(), _ -> new Semaphore(max));
        return CompletableFuture.supplyAsync(() -> {
            semaphore.acquireUninterruptibly();
            return check;
        }, pipelineExecutor).thenComposeAsync(cfg -> {
            final var future = doCheck(client, cfg, pipelineExecutor, timeout, executors);
            return future.whenComplete((_, _) -> semaphore.release());
        }, pipelineExecutor);
    }

    private CompletableFuture<CheckResult> doCheck(final List<RequestListener<?>> client, final CheckConfig it,
                                                   final Executor defaultExecutor, final Duration timeout,
                                                   final Map<CheckType, LazyExecutor> executors) {
        logger.info(() -> "Running '" + it.name() + "' (type=" + it.type() + ")");
        return (switch (it.type()) {
            case HTTP -> httpCheck.check(client, defaultExecutor, timeout, it.name(), it.http());
            case CONNECTION -> connectionCheck.check(it.name(), it.connection(), defaultExecutor);
            case JDBC -> jdbcCheck.check(it.name(), it.jdbc(), executors.computeIfAbsent(JDBC, this::newLazyExecutor));
            case DNS -> dnsCheck.check(defaultExecutor, it.name(), it.dns());
            case X509 -> x509Check.check(defaultExecutor, it.name(), it.x509());
        }).thenApplyAsync(res -> {
            logger.info(() -> "Executed '" + it.name() + "' (type=" + it.type() + "): success=" + res.success() + (res.errorMessage() != null ? ", error=\"" + res.errorMessage() + "\"" : ""));
            return res;
        }, defaultExecutor);
    }

    private LazyExecutor newLazyExecutor(final CheckType type) {
        final var threadPerType = Integer.parseInt(conf.threadPerType().getOrDefault(type.name(), "0"));
        if (threadPerType < 0) {
            return new LazyExecutor(() -> Runnable::run);
        }
        if (threadPerType == 0) {
            return new LazyExecutor(Executors::newVirtualThreadPerTaskExecutor);
        }
        return new LazyExecutor(() -> Executors.newFixedThreadPool(threadPerType, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable worker) {
                final var thread = new Thread(worker, "check-executor-" + type + "-" + count.incrementAndGet());
                thread.setContextClassLoader(CheckExecutor.class.getClassLoader());
                return thread;
            }
        }));
    }

    @RootConfiguration("-")
    public record CheckExecutorConfiguration(
            @Property(documentation = "List of validations to do.", defaultValue = "java.util.List.of()")
            List<CheckConfig> checks,

            @Property(documentation = "OpenTelemetry configuration.")
            OpenTelemetry opentelemetry,

            @Property(value = "execution-timeout", documentation = "Default timeout for the whole execution.", defaultValue = "\"PT15M\"")
            String executionTimeout,

            @Property(value = "default-timeout", documentation = "Default timeout per check.", defaultValue = "\"PT30S\"")
            String defaultTimeout,

            @Property(value = "max-concurrent-per-type", documentation = "Max parallel checks per type.", defaultValue = "java.util.Map.of()")
            Map<String, String> maxConcurrentPerType,

            @Property(value = "thread-per-type", documentation = "Thread mode: <0 synchronous, 0 virtual threads, >0 fixed pool size.", defaultValue = "java.util.Map.of(io.yupiik.statura.CheckExecutor.CheckType.JDBC.name(), \"8\")")
            Map<String, String> threadPerType
    ) {
    }

    public enum CheckType {
        HTTP,
        CONNECTION,
        JDBC,
        DNS,
        X509
    }

    public record CheckConfig(
            @Property(documentation = "Check name (or an internal one is generated).")
            String name,

            @Property(documentation = "Type of the validation.", defaultValue = "io.yupiik.statura.CheckExecutor.CheckType.HTTP")
            CheckType type,

            @Property(documentation = "When type==HTTP, the configuration of the check to do.")
            HttpCheckConfiguration http,

            @Property(documentation = "When type==CONNECTION, the configuration of the connection check to do.")
            ConnectionCheckConfiguration connection,

            @Property(documentation = "When type==JDBC, the configuration of the JDBC check to do.")
            JdbcCheckConfiguration jdbc,

            @Property(documentation = "When type==DNS, the configuration of the DNS check to do.")
            DnsCheckConfiguration dns,

            @Property(documentation = "When type==X509, the configuration of the X509 certificate check to do.")
            X509CheckConfiguration x509
    ) {
    }

    private static class LazyExecutor implements Supplier<Executor> {
        private final Supplier<Executor> delegate;
        private Executor executor;

        private LazyExecutor(final Supplier<Executor> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Executor get() {
            if (executor == null) {
                executor = delegate.get();
            }
            return executor;
        }
    }
}
