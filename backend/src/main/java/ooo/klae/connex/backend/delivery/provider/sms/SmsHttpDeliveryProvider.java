package ooo.klae.connex.backend.delivery.provider.sms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.http.DeadlineBoundHttpTransport;
import ooo.klae.connex.backend.delivery.provider.http.DeadlineBoundHttpTransport.TransportException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A receipt-capable HTTP SMS gateway adapter. It dispatches a rendered text message to a generic JSON
 * send API ({@code {to, from, text}} in, {@code {messageId}} out) over a deadline-bound transport
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
            new DeliveryCapabilities(true, false, false, false, 1);
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
    private final DeadlineBoundHttpTransport transport;
    private final boolean requireHttps;

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
        this.transport = new DeadlineBoundHttpTransport(
                connectTimeout, requestTimeout, maxResponseBytes,
                host -> AiEgressGuard.resolveFetchableHost(host, false), System::nanoTime);
        this.requireHttps = true;
    }

    SmsHttpDeliveryProvider(RestClient restClient, int maxResponseBytes, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.connectTimeout = null;
        this.requestTimeout = null;
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.objectMapper = objectMapper;
        this.transport = null;
        this.requireHttps = true;
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
        if (transport != null && request.providerDeadlineNanos() == null) {
            return DispatchReceipt.rejected("SMS request has no provider deadline");
        }
        URI endpoint = parseEndpoint(target.endpoint(), requireHttps);
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
            response = send(endpoint, apiKey, request.dedupeKey(), body,
                    request.providerDeadlineNanos());
        } catch (TransportException exception) {
            return exception.ambiguous()
                    ? DispatchReceipt.ambiguous(bounded(exception.getMessage()))
                    : DispatchReceipt.rejected(bounded(exception.getMessage()));
        } catch (RuntimeException exception) {
            return DispatchReceipt.rejected(bounded(exception.getMessage()));
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            return DispatchReceipt.rejected("sms gateway rejected with status " + response.statusCode());
        }
        return DispatchReceipt.sent(parseMessageId(response.body()), "sms accepted");
    }

    @PreDestroy
    void shutdown() {
        if (transport != null) {
            transport.close();
        }
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

    private SmsResponse send(
            URI endpoint, String apiKey, String dedupeKey, byte[] body, Long deadlineNanos) {
        if (restClient != null) {
            AiEgressGuard.resolveFetchableHost(endpoint.getHost(), false);
            return exchange(restClient, endpoint, apiKey, dedupeKey, body);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        if (dedupeKey != null) {
            headers.put("Idempotency-Key", dedupeKey);
        }
        DeadlineBoundHttpTransport.Response response =
                transport.post(endpoint, headers, body, deadlineNanos);
        return new SmsResponse(response.statusCode(), response.body());
    }

    private SmsResponse exchange(
            RestClient client, URI endpoint, String apiKey, String dedupeKey, byte[] body) {
        RestClient.RequestBodySpec spec = client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey);
        if (dedupeKey != null) {
            spec.header("Idempotency-Key", dedupeKey);
        }
        return spec.body(body)
                .exchange((httpRequest, httpResponse) -> new SmsResponse(
                        httpResponse.getStatusCode().value(), readBounded(httpResponse.getBody())));
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
