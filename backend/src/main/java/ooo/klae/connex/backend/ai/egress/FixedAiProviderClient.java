package ooo.klae.connex.backend.ai.egress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

import jakarta.annotation.PreDestroy;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;

/**
 * Pinned transport for fixed AI provider hosts. DNS validation and the HTTP exchange share one
 * absolute deadline, redirects and automatic retries are disabled, and response bytes are bounded.
 */
@Component
public class FixedAiProviderClient {
    private static final int BUFFER_BYTES = 8192;
    private static final int MAX_CONCURRENT_RESOLUTIONS = 2;

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final Duration streamIdleTimeout;
    private final HostResolver hostResolver;
    private final ScheduledThreadPoolExecutor deadlineExecutor = deadlineExecutor();
    private final ExecutorService resolverExecutor = resolverExecutor();
    private final Semaphore resolverSlots = new Semaphore(MAX_CONCURRENT_RESOLUTIONS, true);

    @Autowired
    public FixedAiProviderClient(AiProperties aiProperties) {
        this(aiProperties, host -> AiEgressGuard.resolveFetchableHost(host, false));
    }

    FixedAiProviderClient(AiProperties aiProperties, HostResolver hostResolver) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.connectTimeout = duration(aiProperties.getConnectTimeoutMs(), "connect timeout");
        this.requestTimeout = duration(aiProperties.getRequestTimeoutMs(), "request timeout");
        this.maxResponseBytes = positiveInt(aiProperties.getMaxResponseBytes(), "max response bytes");
        this.streamIdleTimeout = positiveDuration(
                aiProperties.getStreamIdleTimeout(), "stream idle timeout");
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
    }

    /**
     * Sends a request to one caller-allowlisted fixed provider host.
     *
     * @param endpoint validated fixed-provider endpoint
     * @param allowedHosts closed host allowlist
     * @param headers sanitized request headers
     * @param contentType request media type
     * @param body bounded request bytes
     * @param deadline absolute provider-call deadline
     * @param operation stable provider operation label
     * @return bounded status and response bytes
     */
    public Response post(
            URI endpoint,
            Set<String> allowedHosts,
            Map<String, String> headers,
            ContentType contentType,
            byte[] body,
            AiRequestDeadline deadline,
            String operation) {
        String host = requireEndpoint(endpoint, allowedHosts, operation);
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(deadline, "deadline");
        requireHeaders(headers, operation);
        InetAddress address = resolve(host, deadline, operation);
        try (PinnedClient pinned = pinnedClient(
                host, address, remainingDuration(deadline, operation), requestTimeout)) {
            return send(pinned, endpoint, headers, contentType, body, deadline, operation);
        }
    }

    /** Sends a pinned response whose successful body is consumed incrementally. */
    public <T> StreamResponse<T> postStream(
            URI endpoint,
            Set<String> allowedHosts,
            Map<String, String> headers,
            ContentType contentType,
            byte[] body,
            AiRequestDeadline deadline,
            String operation,
            AiProviderStreamObserver streamObserver,
            StreamBodyReader<T> bodyReader) {
        String host = requireEndpoint(endpoint, allowedHosts, operation);
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(bodyReader, "bodyReader");
        Objects.requireNonNull(streamObserver, "streamObserver");
        requireHeaders(headers, operation);
        InetAddress address = resolve(host, deadline, operation);
        try (PinnedClient pinned = pinnedClient(
                host, address, remainingDuration(deadline, operation), streamIdleTimeout)) {
            return sendStream(
                    pinned, endpoint, headers, contentType, body, deadline, operation,
                    streamObserver, bodyReader);
        }
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
        resolverExecutor.shutdownNow();
    }

    private Response send(
            PinnedClient pinned,
            URI endpoint,
            Map<String, String> headers,
            ContentType contentType,
            byte[] body,
            AiRequestDeadline deadline,
            String operation) {
        HttpPost request = new HttpPost(endpoint);
        headers.forEach(request::setHeader);
        request.setEntity(new ByteArrayEntity(body, contentType));
        Duration remaining = remainingDuration(deadline, operation);
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(shorter(connectTimeout, remaining)))
                .setResponseTimeout(Timeout.of(requestTimeout))
                .setHardCancellationEnabled(true)
                .build());
        AtomicBoolean deadlineTriggered = new AtomicBoolean();
        ScheduledFuture<?> deadlineTask = deadlineExecutor.schedule(() -> {
            deadlineTriggered.set(true);
            request.cancel();
            pinned.httpClient().close(CloseMode.IMMEDIATE);
        }, remainingNanos(deadline, operation), TimeUnit.NANOSECONDS);
        try {
            Response response = pinned.httpClient().execute(request, providerResponse -> {
                HttpEntity entity = providerResponse.getEntity();
                byte[] responseBody = entity == null
                        ? new byte[0]
                        : readBounded(entity.getContent(), operation);
                return new Response(providerResponse.getCode(), responseBody);
            });
            if (deadline.isExpired()) {
                throw deadlineExceeded(operation);
            }
            return response;
        } catch (IOException exception) {
            if (isDeadlineFailure(exception, deadlineTriggered.get(), request.isCancelled(), deadline)) {
                throw deadlineExceeded(operation);
            }
            throw retryableTransportFailure(operation);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (deadlineTriggered.get() || request.isCancelled() || deadline.isExpired()) {
                throw deadlineExceeded(operation);
            }
            throw transportFailure(operation);
        } finally {
            deadlineTask.cancel(false);
        }
    }

    private <T> StreamResponse<T> sendStream(
            PinnedClient pinned,
            URI endpoint,
            Map<String, String> headers,
            ContentType contentType,
            byte[] body,
            AiRequestDeadline deadline,
            String operation,
            AiProviderStreamObserver streamObserver,
            StreamBodyReader<T> bodyReader) {
        HttpPost request = new HttpPost(endpoint);
        headers.forEach(request::setHeader);
        request.setEntity(new ByteArrayEntity(body, contentType));
        Duration remaining = remainingDuration(deadline, operation);
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(shorter(connectTimeout, remaining)))
                .setResponseTimeout(Timeout.of(streamIdleTimeout))
                .setHardCancellationEnabled(true)
                .build());
        AtomicBoolean deadlineTriggered = new AtomicBoolean();
        ScheduledFuture<?> deadlineTask = deadlineExecutor.schedule(() -> {
            deadlineTriggered.set(true);
            request.cancel();
            pinned.httpClient().close(CloseMode.IMMEDIATE);
        }, remainingNanos(deadline, operation), TimeUnit.NANOSECONDS);
        try {
            streamObserver.onTransportOpen(() -> {
                request.cancel();
                pinned.httpClient().close(CloseMode.IMMEDIATE);
            });
            StreamResponse<T> response = pinned.httpClient().execute(request, providerResponse -> {
                HttpEntity entity = providerResponse.getEntity();
                int status = providerResponse.getCode();
                if (status >= 200 && status <= 299 && entity != null) {
                    T value = bodyReader.read(new BoundedInputStream(
                            entity.getContent(), maxResponseBytes, operation));
                    return new StreamResponse<>(status, value, new byte[0]);
                }
                byte[] responseBody = entity == null
                        ? new byte[0]
                        : readBounded(entity.getContent(), operation);
                return new StreamResponse<>(status, null, responseBody);
            });
            if (deadline.isExpired()) {
                throw deadlineExceeded(operation);
            }
            return response;
        } catch (IOException exception) {
            if (deadlineTriggered.get() || request.isCancelled() || deadline.isExpired()) {
                throw deadlineExceeded(operation);
            }
            if (exception instanceof SocketTimeoutException) {
                throw new ooo.klae.connex.backend.ai.provider.AiProviderIdleTimeoutException(
                        operation + " stream became idle");
            }
            throw retryableTransportFailure(operation);
        } catch (AiProviderException exception) {
            if (deadlineTriggered.get() || request.isCancelled() || deadline.isExpired()) {
                throw deadlineExceeded(operation);
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (deadlineTriggered.get() || request.isCancelled() || deadline.isExpired()) {
                throw deadlineExceeded(operation);
            }
            throw transportFailure(operation);
        } finally {
            streamObserver.onTransportClosed();
            deadlineTask.cancel(false);
        }
    }

    private InetAddress resolve(String host, AiRequestDeadline deadline, String operation) {
        remainingNanos(deadline, operation);
        if (!resolverSlots.tryAcquire()) {
            throw transportFailure(operation);
        }
        try {
            remainingNanos(deadline, operation);
        } catch (AiProviderException exception) {
            resolverSlots.release();
            throw exception;
        }
        Future<InetAddress> resolution;
        try {
            resolution = resolverExecutor.submit(() -> {
                try {
                    return hostResolver.resolve(host);
                } finally {
                    resolverSlots.release();
                }
            });
        } catch (RejectedExecutionException exception) {
            resolverSlots.release();
            throw transportFailure(operation);
        }
        try {
            return resolution.get(remainingNanos(deadline, operation), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw deadlineExceeded(operation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw transportFailure(operation);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AiProviderException providerException) {
                throw providerException;
            }
            throw transportFailure(operation);
        }
    }

    private PinnedClient pinnedClient(
            String host, InetAddress address, Duration remaining, Duration socketTimeout) {
        Timeout connect = Timeout.of(shorter(connectTimeout, remaining));
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(connect)
                .setSocketTimeout(Timeout.of(socketTimeout))
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedHostDnsResolver(host, address))
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnPerRoute(1)
                .setMaxConnTotal(1)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
        return new PinnedClient(httpClient);
    }

    private byte[] readBounded(InputStream input, String operation) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new AiProviderException(operation + " response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String requireEndpoint(URI endpoint, Set<String> allowedHosts, String operation) {
        requireOperation(operation);
        if (endpoint == null || allowedHosts == null || allowedHosts.isEmpty()) {
            throw transportFailure(operation);
        }
        String host = endpoint.getHost();
        if (host == null || !allowedHosts.contains(host)) {
            throw transportFailure(operation);
        }
        return host;
    }

    private static void requireHeaders(Map<String, String> headers, String operation) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (name == null || name.isBlank() || value == null
                    || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw transportFailure(operation);
            }
        }
    }

    private static void requireOperation(String operation) {
        if (operation == null || operation.isBlank()
                || operation.indexOf('\r') >= 0 || operation.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("AI provider operation is required");
        }
    }

    private static Duration remainingDuration(AiRequestDeadline deadline, String operation) {
        return Duration.ofNanos(remainingNanos(deadline, operation));
    }

    private static long remainingNanos(AiRequestDeadline deadline, String operation) {
        long remaining = deadline.remainingNanos();
        if (remaining <= 0) {
            throw deadlineExceeded(operation);
        }
        return remaining;
    }

    private static boolean isDeadlineFailure(
            IOException exception,
            boolean deadlineTriggered,
            boolean requestCancelled,
            AiRequestDeadline deadline) {
        return deadlineTriggered
                || requestCancelled
                || deadline.isExpired()
                || exception instanceof SocketTimeoutException
                        && !(exception instanceof ConnectTimeoutException);
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static AiProviderException deadlineExceeded(String operation) {
        return new AiProviderException(operation + " exceeded its deadline");
    }

    private static AiProviderException transportFailure(String operation) {
        return new AiProviderException(operation + " failed during transport");
    }

    private static AiProviderException retryableTransportFailure(String operation) {
        return new RetryableTransportException(operation + " failed during transport");
    }

    private static Duration duration(long millis, String name) {
        if (millis <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private static int positiveInt(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return value;
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return value;
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                2,
                Thread.ofPlatform().daemon().name("fixed-ai-provider-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ExecutorService resolverExecutor() {
        return Executors.newFixedThreadPool(
                MAX_CONCURRENT_RESOLUTIONS,
                Thread.ofPlatform().daemon().name("fixed-ai-provider-resolver-", 0).factory());
    }

    @Override
    public String toString() {
        return "FixedAiProviderClient[redacted]";
    }

    /** Bounded fixed-provider HTTP response. */
    public record Response(int statusCode, byte[] body) {
        public Response {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "Response[redacted]";
        }
    }

    /** Incrementally consumed fixed-provider response with bounded rejection bytes. */
    public record StreamResponse<T>(int statusCode, T value, byte[] body) {
        public StreamResponse {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /** Synchronous successful-response body decoder. */
    @FunctionalInterface
    public interface StreamBodyReader<T> {
        T read(InputStream input) throws IOException;
    }

    /** Transient fixed-provider connection failure eligible for a caller-owned bounded retry. */
    public static final class RetryableTransportException extends AiProviderException {
        public RetryableTransportException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress resolve(String host);
    }

    private record PinnedClient(CloseableHttpClient httpClient) implements AutoCloseable {
        @Override
        public void close() {
            httpClient.close(CloseMode.GRACEFUL);
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final int maximum;
        private final String operation;
        private int count;

        private BoundedInputStream(InputStream input, int maximum, String operation) {
            super(input);
            this.maximum = maximum;
            this.operation = operation;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                add(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                add(read);
            }
            return read;
        }

        private void add(int amount) {
            if (count > maximum - amount) {
                throw new AiProviderException(
                        operation + " response exceeded the configured size limit");
            }
            count += amount;
        }
    }
}
