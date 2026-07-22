package ooo.klae.connex.backend.delivery.provider.sms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
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
import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A receipt-capable HTTP SMS gateway adapter. It dispatches a rendered text message to a generic JSON
 * send API ({@code {to, from, text}} in, {@code {messageId}} out) over a hardened {@link RestClient}
 * that never follows redirects, bounds the response, and re-vets and pins the destination host
 * immediately before the send, so a rebinding attack cannot redirect the outbound request after
 * validation. SMS is text-only: the rendered subject and HTML body are ignored.
 *
 * <p>Every vendor-specific detail — the request field names and the response id field — is confined to
 * the constants and the single {@link #sendPayload} mapping method in this class, so a second SMS
 * gateway is a new adapter and nothing else.
 */
@Service
public class SmsHttpDeliveryProvider implements MessageDispatcher {

    /** The stable id this provider registers under. */
    public static final String PROVIDER_ID = "sms_http";

    private static final DeliveryCapabilities CAPABILITIES =
            new DeliveryCapabilities(true, false, false, 1);
    private static final String CREDENTIAL_KEY_API = "apiKey";
    private static final int DETAIL_LIMIT = 512;
    private static final int BUFFER_BYTES = 8192;

    private static final String FIELD_TO = "to";
    private static final String FIELD_FROM = "from";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_MESSAGE_ID = "messageId";

    private final RestClient restClient;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;

    /**
     * Builds the production adapter. Because the destination address is validated and pinned per send,
     * the underlying HTTP client is constructed inside {@link #send} rather than here.
     * @param deliveryProperties the delivery transport tuning
     * @param objectMapper the shared JSON mapper
     */
    @Autowired
    public SmsHttpDeliveryProvider(DeliveryProperties deliveryProperties, ObjectMapper objectMapper) {
        this.restClient = null;
        this.connectTimeout = duration(deliveryProperties.getEspConnectTimeoutMs(), "connect timeout");
        this.requestTimeout = duration(deliveryProperties.getEspRequestTimeoutMs(), "request timeout");
        this.maxResponseBytes = positiveInt(deliveryProperties.getEspMaxResponseBytes(), "max response bytes");
        this.objectMapper = objectMapper;
    }

    SmsHttpDeliveryProvider(RestClient restClient, int maxResponseBytes, ObjectMapper objectMapper) {
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
        return Set.of(DeliveryChannel.SMS);
    }

    @Override
    public DeliveryCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request) {
        URI endpoint = parseHttpsEndpoint(target.endpoint());
        if (endpoint == null) {
            return DispatchReceipt.rejected("No usable SMS endpoint is configured");
        }
        String apiKey = target.credentials().get(CREDENTIAL_KEY_API);
        if (apiKey == null || apiKey.isBlank()) {
            return DispatchReceipt.rejected("No usable SMS credential is configured");
        }
        String text = request.content() == null ? null : request.content().bodyText();
        if (text == null || text.isBlank()) {
            return DispatchReceipt.rejected("No SMS text is configured");
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(sendPayload(target, request, text));
        } catch (RuntimeException exception) {
            return DispatchReceipt.rejected("Could not encode the SMS send request");
        }
        SmsResponse response;
        try {
            response = send(endpoint, apiKey, body);
        } catch (RuntimeException exception) {
            return DispatchReceipt.rejected(bounded(exception.getMessage()));
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            return DispatchReceipt.rejected("sms gateway rejected with status " + response.statusCode());
        }
        return DispatchReceipt.sent(parseMessageId(response.body()), "sms accepted");
    }

    private Map<String, Object> sendPayload(ResolvedDeliveryProvider target, DeliveryRequest request, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_TO, request.address());
        payload.put(FIELD_FROM, target.fromAddress());
        payload.put(FIELD_TEXT, text);
        return payload;
    }

    private String parseMessageId(byte[] responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody == null ? new byte[0] : responseBody);
            if (root != null && root.isObject() && root.path(FIELD_MESSAGE_ID).isString()) {
                String messageId = root.path(FIELD_MESSAGE_ID).asString(null);
                return messageId == null || messageId.isBlank() ? null : messageId.trim();
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }

    private SmsResponse send(URI endpoint, String apiKey, byte[] body) {
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
            throw new DeliveryProviderException("SMS transport could not be closed");
        }
    }

    private SmsResponse exchange(RestClient client, URI endpoint, String apiKey, byte[] body) {
        return client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .exchange((httpRequest, httpResponse) -> new SmsResponse(
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
                throw new DeliveryProviderException("SMS response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
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
            throw new IllegalStateException("Delivery SMS " + name + " must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private static int positiveInt(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("Delivery SMS " + name + " must be positive");
        }
        return value;
    }

    private record SmsResponse(int statusCode, byte[] body) {
    }
}
