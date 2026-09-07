package ooo.klae.connex.backend.delivery.provider.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import ooo.klae.connex.backend.ai.egress.PinnedHostDnsResolver;

/**
 * Executes one pinned HTTP POST under an absolute monotonic deadline. The watchdog cancels DNS or
 * the live request and immediately closes its client, so connect, TLS, request writes, and response
 * reads cannot extend a recoverable database lease. Failures after the request is eligible to write
 * are classified as ambiguous because the remote provider may already have accepted the body.
 */
public final class DeadlineBoundHttpTransport implements AutoCloseable {

    private static final int BUFFER_BYTES = 8192;
    private static final int MAX_CONCURRENT_RESOLUTIONS = 2;
    private static final long RESOLVER_ADMISSION_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final HostResolver hostResolver;
    private final LongSupplier nanoTimeSource;
    private final ScheduledThreadPoolExecutor deadlineExecutor = deadlineExecutor();
    private final ExecutorService resolverExecutor = resolverExecutor();
    private final Semaphore resolverSlots = new Semaphore(MAX_CONCURRENT_RESOLUTIONS, true);

    /**
     * Builds a deadline-bound transport.
     * @param connectTimeout the subordinate TCP connection inactivity limit
     * @param requestTimeout the subordinate request/response inactivity limit
     * @param maxResponseBytes the maximum response body size
     * @param hostResolver the destination policy and resolution function
     * @param nanoTimeSource the monotonic clock used by absolute deadlines
     */
    public DeadlineBoundHttpTransport(
            Duration connectTimeout,
            Duration requestTimeout,
            int maxResponseBytes,
            HostResolver hostResolver,
            LongSupplier nanoTimeSource) {
        this.connectTimeout = positive(connectTimeout, "connect timeout");
        this.requestTimeout = positive(requestTimeout, "request timeout");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maximum response bytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
    }

    /**
     * Posts one JSON body under the supplied absolute deadline.
     * @param endpoint the already validated destination
     * @param headers request headers other than content type and accept
     * @param body the JSON request body
     * @param deadlineNanos the absolute {@link System#nanoTime()} deadline
     * @return the bounded provider response
     * @throws TransportException with definitive or ambiguous classification
     */
    public Response post(
            URI endpoint, Map<String, String> headers, byte[] body, long deadlineNanos) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        remainingNanos(deadlineNanos);
        Cancellation cancellation = new Cancellation();
        ScheduledFuture<?> watchdog;
        try {
            watchdog = deadlineExecutor.schedule(
                    cancellation::abort, remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException exception) {
            throw definite("Provider deadline enforcement is unavailable", exception);
        }
        AtomicBoolean egressStarted = new AtomicBoolean();
        try {
            InetAddress address = resolve(endpoint.getHost(), deadlineNanos, cancellation);
            remainingNanos(deadlineNanos);
            try (CloseableHttpClient client = pinnedHttpClient(
                    endpoint.getHost(), address, remainingDuration(deadlineNanos))) {
                cancellation.register(client);
                HttpPost request = request(endpoint, headers, body, deadlineNanos);
                cancellation.register(request);
                remainingNanos(deadlineNanos);
                egressStarted.set(true);
                Response response = client.execute(request, providerResponse -> {
                    HttpEntity entity = providerResponse.getEntity();
                    byte[] responseBody = entity == null
                            ? new byte[0]
                            : readBounded(entity.getContent());
                    return new Response(providerResponse.getCode(), responseBody);
                });
                if (expired(deadlineNanos)) {
                    throw ambiguous("Provider request exceeded its hard deadline", null);
                }
                return response;
            }
        } catch (TransportException exception) {
            if (cancellation.triggered() || expired(deadlineNanos)) {
                throw classifiedDeadlineFailure(egressStarted.get(), exception);
            }
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (cancellation.triggered() || expired(deadlineNanos)) {
                throw classifiedDeadlineFailure(egressStarted.get(), exception);
            }
            if (egressStarted.get()) {
                throw ambiguous("Provider request failed after egress began", exception);
            }
            throw definite("Provider request failed before egress", exception);
        } finally {
            watchdog.cancel(false);
            cancellation.clearResolution();
        }
    }

    @Override
    public void close() {
        deadlineExecutor.shutdownNow();
        resolverExecutor.shutdownNow();
    }

    private InetAddress resolve(
            String host, long deadlineNanos, Cancellation cancellation) {
        boolean acquired;
        try {
            acquired = resolverSlots.tryAcquire(
                    Math.min(remainingNanos(deadlineNanos), RESOLVER_ADMISSION_TIMEOUT_NANOS),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw definite("Provider host resolution was interrupted", exception);
        }
        if (!acquired) {
            if (expired(deadlineNanos)) {
                throw definite("Provider deadline expired before egress", null);
            }
            throw definite("Provider resolver is saturated", null);
        }
        AtomicBoolean taskStarted = new AtomicBoolean();
        Future<InetAddress> resolution;
        try {
            resolution = resolverExecutor.submit(() -> {
                if (!taskStarted.compareAndSet(false, true)) {
                    throw definite("Provider deadline expired before egress", null);
                }
                try {
                    remainingNanos(deadlineNanos);
                    return hostResolver.resolve(host);
                } finally {
                    resolverSlots.release();
                }
            });
            cancellation.registerResolution(resolution);
        } catch (RejectedExecutionException | TransportException exception) {
            resolverSlots.release();
            throw exception;
        }
        long remaining;
        try {
            remaining = remainingNanos(deadlineNanos);
        } catch (TransportException exception) {
            cancelResolution(resolution, taskStarted);
            throw exception;
        }
        try {
            return resolution.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            cancelResolution(resolution, taskStarted);
            throw definite("Provider deadline expired during host resolution", exception);
        } catch (CancellationException exception) {
            cancelResolution(resolution, taskStarted);
            throw definite("Provider deadline expired during host resolution", exception);
        } catch (InterruptedException exception) {
            cancelResolution(resolution, taskStarted);
            Thread.currentThread().interrupt();
            throw definite("Provider host resolution was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof TransportException transportException) {
                throw transportException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw definite("Provider host resolution was rejected", runtimeException);
            }
            throw definite("Provider host resolution failed", cause);
        } finally {
            cancellation.clearResolution();
        }
    }

    private void cancelResolution(Future<InetAddress> resolution, AtomicBoolean taskStarted) {
        resolution.cancel(true);
        if (taskStarted.compareAndSet(false, true)) {
            resolverSlots.release();
        }
    }

    private HttpPost request(
            URI endpoint, Map<String, String> headers, byte[] body, long deadlineNanos) {
        HttpPost request = new HttpPost(endpoint);
        request.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
        request.setHeader("Accept", ContentType.APPLICATION_JSON.getMimeType());
        headers.forEach(request::setHeader);
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(shorter(
                        connectTimeout, remainingDuration(deadlineNanos))))
                .setResponseTimeout(Timeout.of(requestTimeout))
                .setHardCancellationEnabled(true)
                .build());
        return request;
    }

    private CloseableHttpClient pinnedHttpClient(
            String host, InetAddress address, Duration remaining) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(shorter(connectTimeout, remaining)))
                .setSocketTimeout(Timeout.of(requestTimeout))
                .build();
        PoolingHttpClientConnectionManagerBuilder manager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PinnedHostDnsResolver(host, address))
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnPerRoute(1)
                        .setMaxConnTotal(1);
        return HttpClients.custom()
                .setConnectionManager(manager.build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw ambiguous("Provider response exceeded the configured size limit", null);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private long remainingNanos(long deadlineNanos) {
        long remaining = deadlineNanos - nanoTimeSource.getAsLong();
        if (remaining <= 0) {
            throw definite("Provider deadline expired before egress", null);
        }
        return remaining;
    }

    private Duration remainingDuration(long deadlineNanos) {
        return Duration.ofNanos(remainingNanos(deadlineNanos));
    }

    private boolean expired(long deadlineNanos) {
        return deadlineNanos - nanoTimeSource.getAsLong() <= 0;
    }

    private static TransportException classifiedDeadlineFailure(
            boolean egressStarted, Throwable cause) {
        return egressStarted
                ? ambiguous("Provider request exceeded its hard deadline after egress began", cause)
                : definite("Provider deadline expired before egress", cause);
    }

    private static TransportException definite(String message, Throwable cause) {
        return new TransportException(message, false, cause);
    }

    private static TransportException ambiguous(String message, Throwable cause) {
        return new TransportException(message, true, cause);
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                2,
                Thread.ofPlatform().daemon().name("delivery-http-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ExecutorService resolverExecutor() {
        return Executors.newFixedThreadPool(
                MAX_CONCURRENT_RESOLUTIONS,
                Thread.ofPlatform().daemon().name("delivery-http-resolver-", 0).factory());
    }

    /** Resolves and validates one outbound provider hostname. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress resolve(String host);
    }

    /** A bounded HTTP response. */
    public record Response(int statusCode, byte[] body) {
    }

    /** A transport failure classified by whether provider acceptance may have occurred. */
    public static final class TransportException extends RuntimeException {

        private final boolean ambiguous;

        private TransportException(String message, boolean ambiguous, Throwable cause) {
            super(message, cause);
            this.ambiguous = ambiguous;
        }

        /**
         * Reports whether operator reconciliation is required before any replay.
         * @return true when the provider may have accepted the request
         */
        public boolean ambiguous() {
            return ambiguous;
        }
    }

    private static final class Cancellation {

        private final AtomicBoolean triggered = new AtomicBoolean();
        private volatile Future<InetAddress> resolution;
        private volatile HttpPost request;
        private volatile CloseableHttpClient client;

        private void registerResolution(Future<InetAddress> value) {
            resolution = value;
            if (triggered.get()) {
                value.cancel(true);
            }
        }

        private void clearResolution() {
            resolution = null;
        }

        private void register(HttpPost value) {
            request = value;
            if (triggered.get()) {
                value.cancel();
            }
        }

        private void register(CloseableHttpClient value) {
            client = value;
            if (triggered.get()) {
                value.close(CloseMode.IMMEDIATE);
            }
        }

        private void abort() {
            triggered.set(true);
            Future<InetAddress> currentResolution = resolution;
            if (currentResolution != null) {
                currentResolution.cancel(true);
            }
            HttpPost currentRequest = request;
            if (currentRequest != null) {
                currentRequest.cancel();
            }
            CloseableHttpClient currentClient = client;
            if (currentClient != null) {
                currentClient.close(CloseMode.IMMEDIATE);
            }
        }

        private boolean triggered() {
            return triggered.get();
        }
    }
}
