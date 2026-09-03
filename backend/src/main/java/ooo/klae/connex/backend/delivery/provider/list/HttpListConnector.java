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
import java.util.Set;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
 * hardened {@link RestClient} that never follows redirects, bounds the response, and re-vets and pins
 * the destination host immediately before the push, so a rebinding attack cannot redirect the outbound
 * request after validation.
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

    /**
     * Builds the production connector. Because the destination address is validated and pinned per
     * push, the underlying HTTP client is constructed inside {@link #pushAudience} rather than here.
     * @param deliveryProperties the delivery transport tuning
     * @param objectMapper the shared JSON mapper
     */
    @Autowired
    public HttpListConnector(DeliveryProperties deliveryProperties, ObjectMapper objectMapper) {
        this.restClient = null;
        this.connectTimeout = duration(deliveryProperties.getEspConnectTimeoutMs(), "connect timeout");
        this.requestTimeout = duration(deliveryProperties.getEspRequestTimeoutMs(), "request timeout");
        this.maxResponseBytes = positiveInt(deliveryProperties.getEspMaxResponseBytes(), "max response bytes");
        this.objectMapper = objectMapper;
    }

    HttpListConnector(RestClient restClient, int maxResponseBytes, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.connectTimeout = null;
        this.requestTimeout = null;
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.objectMapper = objectMapper;
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
        URI endpoint = parseHttpsEndpoint(target.endpoint());
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
            body = objectMapper.writeValueAsBytes(pushPayload(push));
        } catch (RuntimeException exception) {
            return AudiencePushResult.definiteNoSideEffect(
                    memberCount, "Could not encode the connector push request");
        }
        InetAddress pinnedAddress;
        try {
            pinnedAddress = AiEgressGuard.resolveFetchableHost(endpoint.getHost(), false);
        } catch (RuntimeException exception) {
            return AudiencePushResult.definiteNoSideEffect(memberCount, bounded(exception.getMessage()));
        }
        ListResponse response;
        try {
            response = send(endpoint, pinnedAddress, apiKey, push.idempotencyKey(), body);
        } catch (RuntimeException exception) {
            if (hasCause(exception, ConnectException.class)) {
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
            URI endpoint, InetAddress pinnedAddress, String apiKey, String idempotencyKey, byte[] body) {
        String host = endpoint.getHost();
        if (restClient != null) {
            return exchange(restClient, endpoint, apiKey, idempotencyKey, body);
        }
        try (CloseableHttpClient httpClient = pinnedHttpClient(host, pinnedAddress)) {
            RestClient pinned = RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .build();
            return exchange(pinned, endpoint, apiKey, idempotencyKey, body);
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

    private CloseableHttpClient pinnedHttpClient(String host, InetAddress address) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setSocketTimeout(Timeout.of(requestTimeout))
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.of(requestTimeout))
                .build();
        PoolingHttpClientConnectionManagerBuilder connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PinnedHostDnsResolver(host, address))
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnPerRoute(1)
                        .setMaxConnTotal(1);
        return HttpClients.custom()
                .setConnectionManager(connectionManager.build())
                .setDefaultRequestConfig(requestConfig)
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

    private static URI parseHttpsEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
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

    private static int positiveInt(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("Connector " + name + " must be positive");
        }
        return value;
    }

    private record ListResponse(int statusCode, byte[] body) {
    }
}
