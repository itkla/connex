package ooo.klae.connex.backend.delivery.provider.list;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import java.util.function.LongSupplier;

import jakarta.annotation.PreDestroy;

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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.egress.PinnedHostDnsResolver;
import ooo.klae.connex.backend.delivery.AudienceMember;
import ooo.klae.connex.backend.delivery.AudiencePush;
import ooo.klae.connex.backend.delivery.AudiencePushResult;
import ooo.klae.connex.backend.delivery.AudienceSyncConnector;
import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A generic HTTP list connector. It pushes an eligible audience to a JSON list API
 * ({@code {listId, members:[{email, firstName, lastName}]}} in, {@code {added, failed}} out) over a
 * hardened HTTP transport that never follows redirects, bounds the response, re-vets and pins the
 * destination host immediately before the push, and hard-cancels the request at one wall-clock
 * deadline, so a rebinding attack cannot redirect the outbound request after validation and a
 * continuously active response cannot outlive the export lease.
 *
 * <p>Every vendor-specific detail — the request field names, the credential scheme, and the response
 * tally fields — is confined to the constants and the single {@link #memberPayload} mapping method in
 * this class, so a second connector is a new adapter and nothing else.
 *
 * <p>This generic adapter has no provider-specific atomic non-acceptance contract. Its outcome table
 * is therefore conservative:
 * <table>
 *   <caption>Audience push outcome classification</caption>
 *   <tr><th>Observed result</th><th>Outcome</th></tr>
 *   <tr><td>Local validation, serialization, DNS, or connection refusal before send</td>
 *       <td>{@code DEFINITE_NO_SIDE_EFFECT}</td></tr>
 *   <tr><td>Any non-2xx HTTP response after the request body was sent</td>
 *       <td>{@code AMBIGUOUS}</td></tr>
 *   <tr><td>Hard deadline abort after the request started</td><td>{@code AMBIGUOUS}</td></tr>
 *   <tr><td>Post-send transport failure or incomplete/inconsistent 2xx counters</td>
 *       <td>{@code AMBIGUOUS}</td></tr>
 *   <tr><td>Complete, consistent 2xx acceptance counters</td><td>{@code CONFIRMED}</td></tr>
 * </table>
 */
@Service
public class HttpListConnector implements AudienceSyncConnector {

    /** The stable id this connector registers under. */
    public static final String PROVIDER_ID = "http_list";

    private static final DeliveryCapabilities CAPABILITIES =
            new DeliveryCapabilities(false, true, false, 0);
    private static final String CREDENTIAL_KEY_API = "apiKey";
    private static final int DETAIL_LIMIT = 512;
    private static final int BUFFER_BYTES = 8192;
    private static final int MAX_CONCURRENT_RESOLUTIONS = 2;
    private static final long RESOLVER_ADMISSION_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final String DEADLINE_EXCEEDED_DETAIL =
            "Connector audience push exceeded its hard deadline";
    private static final String MISSING_DEADLINE_DETAIL =
            "Connector audience push has no provider deadline";
    private static final String RESOLVER_SATURATED_DETAIL = "Connector resolver saturated";
    private static final String DEADLINE_EXCEEDED_REASON = "provider_deadline_exceeded";
    private static final String MISSING_DEADLINE_REASON = "provider_deadline_missing";
    private static final String RESOLVER_SATURATED_REASON = "resolver_saturated";

    private static final String FIELD_LIST_ID = "listId";
    private static final String FIELD_MEMBERS = "members";
    private static final String FIELD_EMAIL = "email";
    private static final String FIELD_FIRST_NAME = "firstName";
    private static final String FIELD_LAST_NAME = "lastName";
    private static final String FIELD_ADDED = "added";
    private static final String FIELD_FAILED = "failed";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;
    private final PayloadSerializer payloadSerializer;
    private final HostResolver hostResolver;
    private final LongSupplier nanoTimeSource;
    private final boolean requireHttps;
    private final ScheduledThreadPoolExecutor deadlineExecutor = deadlineExecutor();
    private final ExecutorService resolverExecutor = resolverExecutor();
    private final Semaphore resolverSlots = new Semaphore(MAX_CONCURRENT_RESOLUTIONS, true);

    /**
     * Builds the production connector. Because the destination address is validated and pinned per
     * push, the underlying HTTP client is constructed inside {@link #pushAudience} rather than here.
     * @param deliveryProperties the delivery transport tuning
     * @param objectMapper the shared JSON mapper
     */
    @Autowired
    public HttpListConnector(DeliveryProperties deliveryProperties, ObjectMapper objectMapper) {
        this(deliveryProperties, objectMapper,
                host -> AiEgressGuard.resolveFetchableHost(host, false), true,
                objectMapper::writeValueAsBytes, System::nanoTime);
    }

    HttpListConnector(RestClient restClient, int maxResponseBytes, ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.connectTimeout = null;
        this.requestTimeout = null;
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.payloadSerializer = objectMapper::writeValueAsBytes;
        this.hostResolver = host -> AiEgressGuard.resolveFetchableHost(host, false);
        this.nanoTimeSource = System::nanoTime;
        this.requireHttps = true;
    }

    HttpListConnector(
            DeliveryProperties deliveryProperties,
            ObjectMapper objectMapper,
            HostResolver hostResolver) {
        this(deliveryProperties, objectMapper, hostResolver, false,
                objectMapper::writeValueAsBytes, System::nanoTime);
    }

    HttpListConnector(
            DeliveryProperties deliveryProperties,
            ObjectMapper objectMapper,
            HostResolver hostResolver,
            boolean requireHttps,
            PayloadSerializer payloadSerializer,
            LongSupplier nanoTimeSource) {
        Objects.requireNonNull(deliveryProperties, "deliveryProperties");
        this.restClient = null;
        this.connectTimeout = duration(deliveryProperties.getEspConnectTimeoutMs(), "connect timeout");
        this.requestTimeout = duration(deliveryProperties.getEspRequestTimeoutMs(), "request timeout");
        positiveDuration(deliveryProperties.audienceExportProviderDeadline(), "provider deadline");
        this.maxResponseBytes = positiveInt(
                deliveryProperties.getEspMaxResponseBytes(), "max response bytes");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.payloadSerializer = Objects.requireNonNull(payloadSerializer, "payloadSerializer");
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
        this.requireHttps = requireHttps;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<DeliveryChannel> channels() {
        return Set.of(DeliveryChannel.EMAIL);
    }

    @Override
    public DeliveryCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public AudiencePushResult pushAudience(ResolvedDeliveryProvider target, AudiencePush push) {
        int memberCount = push.members().size();
        ProviderCallDeadline deadline = providerCallDeadline(push);
        if (deadline == null && restClient == null) {
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, MISSING_DEADLINE_DETAIL, MISSING_DEADLINE_REASON);
        }
        if (deadline != null && deadline.isExpired()) {
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, DEADLINE_EXCEEDED_DETAIL, DEADLINE_EXCEEDED_REASON);
        }
        URI endpoint = parseEndpoint(target.endpoint(), requireHttps);
        if (endpoint == null) {
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, "No usable connector endpoint is configured");
        }
        String apiKey = target.credentials().get(CREDENTIAL_KEY_API);
        if (apiKey == null || apiKey.isBlank()) {
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, "No usable connector credential is configured");
        }
        if (push.externalListId() == null || push.externalListId().isBlank()) {
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, "No external list is configured for the connector");
        }
        byte[] body;
        try {
            if (deadline != null) {
                remainingNanos(deadline);
            }
            body = payloadSerializer.serialize(pushPayload(push));
            if (deadline != null) {
                remainingNanos(deadline);
            }
        } catch (RuntimeException exception) {
            if (deadline != null && deadline.isExpired()) {
                return AudiencePushResult.definiteNoSideEffect(
                        memberCount, DEADLINE_EXCEEDED_DETAIL, DEADLINE_EXCEEDED_REASON);
            }
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, "Could not encode the connector push request");
        }
        InetAddress pinnedAddress;
        try {
            pinnedAddress = resolve(endpoint.getHost(), deadline);
        } catch (RuntimeException exception) {
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, bounded(exception.getMessage()), failureReason(exception));
        }
        ListResponse response;
        try {
            response = send(endpoint, pinnedAddress, apiKey, push.idempotencyKey(), body, deadline);
        } catch (RuntimeException exception) {
            if (exception instanceof DefinitePreSendException
                    || hasCause(exception, ConnectException.class)) {
                return AudiencePushResult.definiteNoSideEffect(memberCount, bounded(exception.getMessage()));
            }
            return AudiencePushResult.ambiguous(memberCount, bounded(exception.getMessage()));
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            return AudiencePushResult.ambiguous(
                    memberCount,
                    "connector returned status " + response.statusCode() + " after the request was sent");
        }
        return parseResult(response.body(), memberCount);
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
        resolverExecutor.shutdownNow();
    }

    private Map<String, Object> pushPayload(AudiencePush push) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_LIST_ID, push.externalListId());
        List<Map<String, Object>> members = new ArrayList<>(push.members().size());
        for (AudienceMember member : push.members()) {
            members.add(memberPayload(member));
        }
        payload.put(FIELD_MEMBERS, members);
        return payload;
    }

    private Map<String, Object> memberPayload(AudienceMember member) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(FIELD_EMAIL, member.email());
        if (member.firstName() != null && !member.firstName().isBlank()) {
            entry.put(FIELD_FIRST_NAME, member.firstName());
        }
        if (member.lastName() != null && !member.lastName().isBlank()) {
            entry.put(FIELD_LAST_NAME, member.lastName());
        }
        return entry;
    }

    private AudiencePushResult parseResult(byte[] responseBody, int memberCount) {
        try {
            JsonNode root = objectMapper.readTree(responseBody == null ? new byte[0] : responseBody);
            if (root != null && root.isObject()) {
                JsonNode addedNode = root.get(FIELD_ADDED);
                JsonNode failedNode = root.get(FIELD_FAILED);
                if (isNonNegativeInt(addedNode) && isNonNegativeInt(failedNode)) {
                    int added = addedNode.intValue();
                    int failed = failedNode.intValue();
                    if ((long) added + failed == memberCount) {
                        return new AudiencePushResult(added, failed, "connector accepted");
                    }
                }
            }
        } catch (RuntimeException exception) {
            return unconfirmed(memberCount);
        }
        return unconfirmed(memberCount);
    }

    private static AudiencePushResult unconfirmed(int memberCount) {
        return AudiencePushResult.ambiguous(
                memberCount, "provider returned an incomplete or inconsistent response");
    }

    private ListResponse send(
            URI endpoint,
            InetAddress pinnedAddress,
            String apiKey,
            String idempotencyKey,
            byte[] body,
            ProviderCallDeadline deadline) {
        String host = endpoint.getHost();
        if (restClient != null) {
            return exchange(restClient, endpoint, apiKey, idempotencyKey, body);
        }
        try (CloseableHttpClient httpClient = pinnedHttpClient(
                host, pinnedAddress, remainingDuration(deadline))) {
            return exchange(httpClient, endpoint, apiKey, idempotencyKey, body, deadline);
        } catch (IOException exception) {
            throw new DeliveryProviderException("Connector transport could not be closed");
        }
    }

    private ListResponse exchange(
            RestClient client, URI endpoint, String apiKey, String idempotencyKey, byte[] body) {
        RestClient.RequestBodySpec request = client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey);
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_HEADER, idempotencyKey);
        }
        return request.body(body)
                .exchange((httpRequest, httpResponse) -> new ListResponse(
                        httpResponse.getStatusCode().value(), readBounded(httpResponse.getBody())));
    }

    private ListResponse exchange(
            CloseableHttpClient httpClient,
            URI endpoint,
            String apiKey,
            String idempotencyKey,
            byte[] body,
            ProviderCallDeadline deadline) {
        HttpPost request = new HttpPost(endpoint);
        request.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
        request.setHeader("Accept", ContentType.APPLICATION_JSON.getMimeType());
        request.setHeader("Authorization", "Bearer " + apiKey);
        if (idempotencyKey != null) {
            request.setHeader(IDEMPOTENCY_HEADER, idempotencyKey);
        }
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(shorter(connectTimeout, remainingDuration(deadline))))
                .setResponseTimeout(Timeout.of(requestTimeout))
                .setHardCancellationEnabled(true)
                .build());
        AtomicBoolean deadlineTriggered = new AtomicBoolean();
        ScheduledFuture<?> deadlineTask;
        try {
            deadlineTask = deadlineExecutor.schedule(() -> {
                deadlineTriggered.set(true);
                request.cancel();
                httpClient.close(CloseMode.IMMEDIATE);
            }, remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException exception) {
            throw new DefinitePreSendException(
                    "Connector deadline enforcement is unavailable", "connector_definitive_failure");
        }
        try {
            ListResponse response = httpClient.execute(request, providerResponse -> {
                HttpEntity entity = providerResponse.getEntity();
                byte[] responseBody = entity == null
                        ? new byte[0]
                        : readBounded(entity.getContent());
                return new ListResponse(providerResponse.getCode(), responseBody);
            });
            if (deadline.isExpired()) {
                throw deadlineExceededAfterSend();
            }
            return response;
        } catch (IOException exception) {
            if (deadlineTriggered.get() || request.isCancelled() || deadline.isExpired()) {
                throw deadlineExceededAfterSend();
            }
            throw new DeliveryProviderException("Connector request failed during transport", exception);
        } catch (RuntimeException exception) {
            if (deadlineTriggered.get() || request.isCancelled() || deadline.isExpired()) {
                throw deadlineExceededAfterSend();
            }
            throw exception;
        } finally {
            deadlineTask.cancel(false);
        }
    }

    private CloseableHttpClient pinnedHttpClient(
            String host, InetAddress address, Duration remaining) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(shorter(connectTimeout, remaining)))
                .setSocketTimeout(Timeout.of(requestTimeout))
                .build();
        PoolingHttpClientConnectionManagerBuilder connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PinnedHostDnsResolver(host, address))
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnPerRoute(1)
                        .setMaxConnTotal(1);
        return HttpClients.custom()
                .setConnectionManager(connectionManager.build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    private InetAddress resolve(String host, ProviderCallDeadline deadline) {
        if (deadline == null) {
            return hostResolver.resolve(host);
        }
        boolean acquired;
        try {
            acquired = resolverSlots.tryAcquire(
                    Math.min(remainingNanos(deadline), RESOLVER_ADMISSION_TIMEOUT_NANOS),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeliveryProviderException("Connector host resolution was interrupted");
        }
        if (!acquired) {
            if (deadline.isExpired()) {
                throw deadlineExceededBeforeSend();
            }
            throw new DefinitePreSendException(RESOLVER_SATURATED_DETAIL, RESOLVER_SATURATED_REASON);
        }
        AtomicBoolean taskStarted = new AtomicBoolean();
        Future<InetAddress> resolution;
        try {
            remainingNanos(deadline);
            resolution = resolverExecutor.submit(() -> {
                if (!taskStarted.compareAndSet(false, true)) {
                    throw deadlineExceededBeforeSend();
                }
                try {
                    remainingNanos(deadline);
                    return hostResolver.resolve(host);
                } finally {
                    resolverSlots.release();
                }
            });
        } catch (RejectedExecutionException | DefinitePreSendException exception) {
            resolverSlots.release();
            throw exception;
        }
        try {
            return resolution.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (DefinitePreSendException exception) {
            cancelResolution(resolution, taskStarted);
            throw exception;
        } catch (TimeoutException exception) {
            cancelResolution(resolution, taskStarted);
            throw deadlineExceededBeforeSend();
        } catch (InterruptedException exception) {
            cancelResolution(resolution, taskStarted);
            Thread.currentThread().interrupt();
            throw new DeliveryProviderException("Connector host resolution was interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new DeliveryProviderException("Connector host resolution failed");
        }
    }

    private void cancelResolution(Future<InetAddress> resolution, AtomicBoolean taskStarted) {
        resolution.cancel(true);
        if (taskStarted.compareAndSet(false, true)) {
            resolverSlots.release();
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new DeliveryProviderException("Connector response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static boolean isNonNegativeInt(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 0;
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static URI parseEndpoint(String endpoint, boolean requireHttps) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            boolean acceptedScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || !requireHttps && "http".equalsIgnoreCase(uri.getScheme());
            if (!uri.isAbsolute() || !acceptedScheme
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String bounded(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.length() > DETAIL_LIMIT ? trimmed.substring(0, DETAIL_LIMIT) : trimmed;
    }

    private static Duration duration(long millis, String name) {
        if (millis <= 0) {
            throw new IllegalStateException("Connector " + name + " must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("Connector " + name + " must be positive");
        }
        return value;
    }

    private static int positiveInt(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("Connector " + name + " must be positive");
        }
        return value;
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration remainingDuration(ProviderCallDeadline deadline) {
        return Duration.ofNanos(remainingNanos(deadline));
    }

    private static long remainingNanos(ProviderCallDeadline deadline) {
        long remaining = deadline.remainingNanos();
        if (remaining <= 0) {
            throw deadlineExceededBeforeSend();
        }
        return remaining;
    }

    private static DefinitePreSendException deadlineExceededBeforeSend() {
        return new DefinitePreSendException(DEADLINE_EXCEEDED_DETAIL, DEADLINE_EXCEEDED_REASON);
    }

    private static String failureReason(RuntimeException exception) {
        return exception instanceof DefinitePreSendException definite
                ? definite.failureReason()
                : "connector_definitive_failure";
    }

    private static DeliveryProviderException deadlineExceededAfterSend() {
        return new DeliveryProviderException(DEADLINE_EXCEEDED_DETAIL);
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                2,
                Thread.ofPlatform().daemon().name("http-list-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ExecutorService resolverExecutor() {
        return Executors.newFixedThreadPool(
                MAX_CONCURRENT_RESOLUTIONS,
                Thread.ofPlatform().daemon().name("http-list-resolver-", 0).factory());
    }

    private ProviderCallDeadline providerCallDeadline(AudiencePush push) {
        Long absoluteDeadlineNanos = push.providerDeadlineNanos();
        return absoluteDeadlineNanos == null
                ? null
                : new ProviderCallDeadline(absoluteDeadlineNanos, nanoTimeSource);
    }

    interface HostResolver {
        InetAddress resolve(String host);
    }

    interface PayloadSerializer {
        byte[] serialize(Object value);
    }

    private static final class DefinitePreSendException extends DeliveryProviderException {

        private final String failureReason;

        private DefinitePreSendException(String message, String failureReason) {
            super(message);
            this.failureReason = failureReason;
        }

        private String failureReason() {
            return failureReason;
        }
    }

    private record ProviderCallDeadline(long deadlineNanos, LongSupplier nanoTimeSource) {
        private long remainingNanos() {
            return deadlineNanos - nanoTimeSource.getAsLong();
        }

        private boolean isExpired() {
            return remainingNanos() <= 0;
        }
    }

    private record ListResponse(int statusCode, byte[] body) {
    }
}
