package ooo.klae.connex.backend.delivery.provider.list;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
            return AudiencePushResult.failed(memberCount, "No usable connector endpoint is configured");
        }
        String apiKey = target.credentials().get(CREDENTIAL_KEY_API);
        if (apiKey == null || apiKey.isBlank()) {
            return AudiencePushResult.failed(memberCount, "No usable connector credential is configured");
        }
        if (push.externalListId() == null || push.externalListId().isBlank()) {
            return AudiencePushResult.failed(memberCount, "No external list is configured for the connector");
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(pushPayload(push));
        } catch (RuntimeException exception) {
            return AudiencePushResult.failed(memberCount, "Could not encode the connector push request");
        }
        ListResponse response;
        try {
            response = send(endpoint, apiKey, body);
        } catch (RuntimeException exception) {
            return AudiencePushResult.failed(memberCount, bounded(exception.getMessage()));
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            return AudiencePushResult.failed(memberCount, "connector rejected with status " + response.statusCode());
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
                int added = intField(root, FIELD_ADDED, memberCount);
                int failed = intField(root, FIELD_FAILED, 0);
                return new AudiencePushResult(clamp(added, memberCount), clamp(failed, memberCount), "connector accepted");
            }
        } catch (RuntimeException exception) {
            return new AudiencePushResult(memberCount, 0, "connector accepted");
        }
        return new AudiencePushResult(memberCount, 0, "connector accepted");
    }

    private ListResponse send(URI endpoint, String apiKey, byte[] body) {
        String host = endpoint.getHost();
        InetAddress pinnedAddress = AiEgressGuard.resolveFetchableHost(host, false);
        if (restClient != null) {
            return exchange(restClient, endpoint, apiKey, body);
        }
        try (CloseableHttpClient httpClient = pinnedHttpClient(host, pinnedAddress)) {
            RestClient pinned = RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .build();
            return exchange(pinned, endpoint, apiKey, body);
        } catch (IOException exception) {
            throw new DeliveryProviderException("Connector transport could not be closed");
        }
    }

    private ListResponse exchange(RestClient client, URI endpoint, String apiKey, byte[] body) {
        return client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
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

    private static int intField(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.asInt() : fallback;
    }

    private static int clamp(int value, int max) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, max);
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
