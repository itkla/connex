package ooo.klae.connex.backend.ai.provider.openai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
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

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import jakarta.annotation.PreDestroy;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.egress.PinnedHostDnsResolver;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;

/**
 * Minimal OpenAI-compatible transport. The client never follows redirects, revalidates and
 * re-vets the organization-configured host immediately before every send, conditionally applies
 * bearer authentication, and bounds response bytes.
 */
@Component
public class OpenAiCompatibleClient {
    private static final int BUFFER_BYTES = 8192;
    private static final int MAX_CONCURRENT_RESOLUTIONS = 2;

    private final RestClient restClient;
    private final int maxResponseBytes;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final AiEndpointAddressValidator endpointAddressValidator;
    private final ScheduledThreadPoolExecutor deadlineExecutor = deadlineExecutor();
    private final ExecutorService resolverExecutor = resolverExecutor();
    private final Semaphore resolverSlots = new Semaphore(MAX_CONCURRENT_RESOLUTIONS, true);

    @Autowired
    public OpenAiCompatibleClient(
            AiProperties aiProperties, AiEndpointAddressValidator endpointAddressValidator) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.restClient = null;
        this.connectTimeout = duration(aiProperties.getConnectTimeoutMs(), "connect timeout");
        this.requestTimeout = duration(aiProperties.getRequestTimeoutMs(), "request timeout");
        this.maxResponseBytes = positiveInt(aiProperties.getMaxResponseBytes(), "max response bytes");
        this.endpointAddressValidator = Objects.requireNonNull(endpointAddressValidator, "endpointAddressValidator");
    }

    OpenAiCompatibleClient(RestClient restClient, int maxResponseBytes) {
        this(restClient, maxResponseBytes, new AiEndpointAddressValidator(new AiProperties()));
    }

    OpenAiCompatibleClient(
            RestClient restClient,
            int maxResponseBytes,
            AiEndpointAddressValidator endpointAddressValidator) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.connectTimeout = null;
        this.requestTimeout = null;
        this.endpointAddressValidator = Objects.requireNonNull(endpointAddressValidator, "endpointAddressValidator");
    }

    /**
     * Sends an OpenAI-compatible chat-completions request.
     * @param endpoint validated organization-configured chat-completions endpoint
     * @param allowInternalEndpoint whether private endpoint addresses and HTTP are permitted
     * @param credentials decrypted optional provider credentials
     * @param requestBodyJson chat-completions request body JSON
     * @return provider response body JSON
     */
    public String complete(URI endpoint, boolean allowInternalEndpoint, AiCredentials credentials,
            String requestBodyJson) {
        AiRequestDeadline deadline = requestTimeout == null
                ? null
                : AiRequestDeadline.afterMillis(requestTimeout.toMillis());
        return complete(endpoint, allowInternalEndpoint, credentials, requestBodyJson, deadline);
    }

    String complete(
            URI endpoint,
            boolean allowInternalEndpoint,
            AiCredentials credentials,
            String requestBodyJson,
            AiRequestDeadline deadline) {
        String host = requireEndpoint(endpoint, allowInternalEndpoint);
        if (credentials == null) {
            throw new AiProviderException("OpenAI-compatible credentials are required");
        }
        requireText(requestBodyJson, "request body");
        String apiKey = credentials.get("apiKey");
        if (apiKey != null && !apiKey.isBlank()) {
            requireHeaderValue(apiKey);
        }
        byte[] body = requestBodyJson.getBytes(StandardCharsets.UTF_8);
        OpenAiCompatibleResponse response;
        try {
            InetAddress pinnedAddress = resolveFetchable(host, allowInternalEndpoint, deadline);
            if (restClient != null) {
                response = sendOnce(restClient, endpoint, apiKey, body);
            } else {
                Objects.requireNonNull(deadline, "deadline");
                try (PinnedRestClient pinned = pinnedRestClient(
                        host, pinnedAddress, remainingDuration(deadline))) {
                    response = sendOnce(pinned, endpoint, apiKey, body, deadline);
                }
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        } catch (RuntimeException exception) {
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new AiProviderRequestRejectedException(
                    "OpenAI-compatible", response.statusCode());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
        resolverExecutor.shutdownNow();
    }

    private OpenAiCompatibleResponse sendOnce(
            RestClient client, URI endpoint, String apiKey, byte[] body) {
        RestClient.RequestBodySpec spec = client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey);
        }
        return spec.body(body)
                .exchange((request, response) -> new OpenAiCompatibleResponse(
                        response.getStatusCode().value(), readBounded(response.getBody())));
    }

    private OpenAiCompatibleResponse sendOnce(
            PinnedRestClient pinned,
            URI endpoint,
            String apiKey,
            byte[] body,
            AiRequestDeadline deadline) {
        Duration remaining = remainingDuration(deadline);
        HttpPost request = new HttpPost(endpoint);
        request.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        if (apiKey != null && !apiKey.isBlank()) {
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
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
        }, remainingNanos(deadline), TimeUnit.NANOSECONDS);
        try {
            OpenAiCompatibleResponse response = pinned.httpClient().execute(request, providerResponse -> {
                HttpEntity entity = providerResponse.getEntity();
                byte[] responseBody = entity == null
                        ? new byte[0]
                        : readBounded(entity.getContent());
                return new OpenAiCompatibleResponse(providerResponse.getCode(), responseBody);
            });
            if (deadline.isExpired()) {
                throw deadlineExceeded();
            }
            return response;
        } catch (IOException exception) {
            throw new AiProviderException(isDeadlineFailure(
                    exception, deadlineTriggered.get(), request.isCancelled(), deadline)
                    ? "OpenAI-compatible invocation exceeded its deadline"
                    : "OpenAI-compatible invocation failed during transport");
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (deadlineTriggered.get() || request.isCancelled() || deadline.isExpired()) {
                throw deadlineExceeded();
            }
            throw exception;
        } finally {
            deadlineTask.cancel(false);
        }
    }

    private InetAddress resolveFetchable(
            String host,
            boolean allowInternalEndpoint,
            AiRequestDeadline deadline) {
        if (deadline == null) {
            return endpointAddressValidator.resolveFetchable(host, allowInternalEndpoint);
        }
        if (!resolverSlots.tryAcquire()) {
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        }
        Future<InetAddress> resolution;
        try {
            resolution = resolverExecutor.submit(() -> {
                try {
                    return endpointAddressValidator.resolveFetchable(host, allowInternalEndpoint);
                } finally {
                    resolverSlots.release();
                }
            });
        } catch (RejectedExecutionException exception) {
            resolverSlots.release();
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        }
        try {
            return resolution.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw deadlineExceeded();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AiProviderException providerException) {
                throw providerException;
            }
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        }
    }

    private PinnedRestClient pinnedRestClient(String host, InetAddress address, Duration remaining) {
        Timeout connect = Timeout.of(shorter(connectTimeout, remaining));
        Timeout request = Timeout.of(requestTimeout);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(connect)
            .setSocketTimeout(request)
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
        return new PinnedRestClient(httpClient);
    }

    private static Duration remainingDuration(AiRequestDeadline deadline) {
        return Duration.ofNanos(remainingNanos(deadline));
    }

    private static long remainingNanos(AiRequestDeadline deadline) {
        long remaining = deadline.remainingNanos();
        if (remaining <= 0) {
            throw deadlineExceeded();
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

    private static AiProviderException deadlineExceeded() {
        return new AiProviderException("OpenAI-compatible invocation exceeded its deadline");
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new AiProviderException(
                        "OpenAI-compatible response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String requireEndpoint(URI endpoint, boolean allowInternalEndpoint) {
        if (endpoint == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        String scheme = endpoint.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        if ("http".equalsIgnoreCase(scheme) && !allowInternalEndpoint) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        return host;
    }

    private static void requireHeaderValue(String apiKey) {
        if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new AiProviderException("Invalid OpenAI-compatible credentials");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AiProviderException("OpenAI-compatible " + name + " is required");
        }
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

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform().daemon().name("openai-compatible-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ExecutorService resolverExecutor() {
        return Executors.newFixedThreadPool(
                MAX_CONCURRENT_RESOLUTIONS,
                Thread.ofPlatform().daemon().name("openai-compatible-resolver-", 0).factory());
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleClient[redacted]";
    }

    private record OpenAiCompatibleResponse(int statusCode, byte[] body) {
        @Override
        public String toString() {
            return "OpenAiCompatibleResponse[redacted]";
        }
    }

    private record PinnedRestClient(CloseableHttpClient httpClient) implements AutoCloseable {
        @Override
        public void close() {
            httpClient.close(CloseMode.GRACEFUL);
        }
    }
}
