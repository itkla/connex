package ooo.klae.connex.backend.delivery.provider.esp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryEvent;
import ooo.klae.connex.backend.delivery.DeliveryEventType;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ProviderEventSource;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.http.DeadlineBoundHttpTransport;
import ooo.klae.connex.backend.delivery.provider.http.DeadlineBoundHttpTransport.TransportException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A receipt-capable HTTP email service provider (ESP) adapter. It dispatches a rendered message to a
 * generic JSON send API (SendGrid/Postmark-style: {@code {to, from, subject, html, text}} in,
 * {@code {messageId}} out) over a deadline-bound transport that never follows redirects, bounds the
 * response, and re-vets and pins the destination host immediately before the send. It also
 * authenticates and translates the provider's webhook events back into normalized
 * {@link DeliveryEvent}s.
 *
 * <p>Every vendor-specific detail — the request field names, the response id field, the signature
 * header, and the event-payload vocabulary — is confined to the constants and the two private mapping
 * methods in this class, so a second ESP is a new adapter and nothing else.
 */
@Service
public class HttpEspDeliveryProvider implements MessageDispatcher, ProviderEventSource {

    /** The stable id this provider registers under. */
    public static final String PROVIDER_ID = "http_esp";

    /** The header a provider must carry the hex HMAC-SHA256 of the raw webhook body in. */
    public static final String SIGNATURE_HEADER = "x-connex-signature";

    private static final DeliveryCapabilities CAPABILITIES =
            new DeliveryCapabilities(true, false, true, false, 1);
    private static final String CREDENTIAL_KEY_API = "apiKey";
    private static final String CREDENTIAL_KEY_WEBHOOK_SECRET = "webhookSecret";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int DETAIL_LIMIT = 512;
    private static final int BUFFER_BYTES = 8192;

    private static final String FIELD_TO = "to";
    private static final String FIELD_FROM = "from";
    private static final String FIELD_FROM_NAME = "fromName";
    private static final String FIELD_SUBJECT = "subject";
    private static final String FIELD_HTML = "html";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_MESSAGE_ID = "messageId";
    private static final String FIELD_EVENTS = "events";
    private static final String FIELD_EVENT = "event";
    private static final String FIELD_EVENT_ID = "eventId";
    private static final String FIELD_BOUNCE_TYPE = "bounceType";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_REASON = "reason";
    private static final String EVENT_DELIVERED = "delivered";
    private static final String EVENT_BOUNCE = "bounce";
    private static final String EVENT_COMPLAINT = "complaint";
    private static final String EVENT_FAILED = "failed";
    private static final String BOUNCE_HARD = "hard";

    private final RestClient restClient;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;
    private final DeadlineBoundHttpTransport transport;
    private final boolean requireHttps;

    /**
     * Builds the production adapter. Because the destination address is validated and pinned per
     * send, the underlying HTTP client is constructed inside {@link #send} rather than here.
     * @param deliveryProperties the delivery transport tuning
     * @param objectMapper the shared JSON mapper
     */
    @Autowired
    public HttpEspDeliveryProvider(DeliveryProperties deliveryProperties, ObjectMapper objectMapper) {
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

    HttpEspDeliveryProvider(RestClient restClient, int maxResponseBytes, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.connectTimeout = null;
        this.requestTimeout = null;
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.objectMapper = objectMapper;
        this.transport = null;
        this.requireHttps = true;
    }

    HttpEspDeliveryProvider(
            DeadlineBoundHttpTransport transport,
            int maxResponseBytes,
            ObjectMapper objectMapper,
            boolean requireHttps) {
        this.restClient = null;
        this.connectTimeout = null;
        this.requestTimeout = null;
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.objectMapper = objectMapper;
        this.transport = transport;
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
    public DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request) {
        if (transport != null && request.providerDeadlineNanos() == null) {
            return DispatchReceipt.rejected("ESP request has no provider deadline");
        }
        URI endpoint = parseEndpoint(target.endpoint(), requireHttps);
        if (endpoint == null) {
            return DispatchReceipt.rejected("No usable ESP endpoint is configured");
        }
        String apiKey = target.credentials().get(CREDENTIAL_KEY_API);
        if (apiKey == null || apiKey.isBlank()) {
            return DispatchReceipt.rejected("No usable ESP credential is configured");
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(sendPayload(target, request));
        } catch (RuntimeException exception) {
            return DispatchReceipt.rejected("Could not encode the ESP send request");
        }
        EspResponse response;
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
            return DispatchReceipt.rejected("esp rejected with status " + response.statusCode());
        }
        return DispatchReceipt.sent(parseMessageId(response.body()), "esp accepted");
    }

    @PreDestroy
    void shutdown() {
        if (transport != null) {
            transport.close();
        }
    }

    @Override
    public void verifySignature(ResolvedDeliveryProvider target, byte[] rawBody, Map<String, String> headers) {
        String secret = target.credentials().get(CREDENTIAL_KEY_WEBHOOK_SECRET);
        if (secret == null || secret.isBlank()) {
            throw new DeliveryProviderException("No webhook secret is configured");
        }
        String provided = headers == null ? null : headers.get(SIGNATURE_HEADER);
        if (provided == null || provided.isBlank()) {
            throw new DeliveryProviderException("Webhook signature is missing");
        }
        byte[] expected = hmacSha256(secret, rawBody == null ? new byte[0] : rawBody);
        byte[] presented = decodeHex(provided.trim());
        if (presented == null || !java.security.MessageDigest.isEqual(expected, presented)) {
            throw new DeliveryProviderException("Webhook signature does not match");
        }
    }

    @Override
    public List<DeliveryEvent> translate(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null ? new byte[0] : rawBody);
        } catch (RuntimeException exception) {
            throw new DeliveryProviderException("Could not parse the ESP webhook payload");
        }
        List<DeliveryEvent> events = new ArrayList<>();
        for (JsonNode node : eventNodes(root)) {
            DeliveryEvent event = mapEvent(node);
            if (event != null) {
                events.add(event);
            }
        }
        return List.copyOf(events);
    }

    private Map<String, Object> sendPayload(ResolvedDeliveryProvider target, DeliveryRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_TO, request.address());
        payload.put(FIELD_FROM, target.fromAddress());
        if (target.fromName() != null && !target.fromName().isBlank()) {
            payload.put(FIELD_FROM_NAME, target.fromName());
        }
        payload.put(FIELD_SUBJECT, request.content().subject());
        payload.put(FIELD_HTML, request.content().bodyHtml());
        if (request.content().bodyText() != null) {
            payload.put(FIELD_TEXT, request.content().bodyText());
        }
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

    private Iterable<JsonNode> eventNodes(JsonNode root) {
        if (root == null) {
            return List.of();
        }
        if (root.isArray()) {
            return root;
        }
        if (root.isObject() && root.path(FIELD_EVENTS).isArray()) {
            return root.path(FIELD_EVENTS);
        }
        if (root.isObject()) {
            return List.of(root);
        }
        return List.of();
    }

    private DeliveryEvent mapEvent(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String messageId = text(node, FIELD_MESSAGE_ID);
        if (messageId == null) {
            return null;
        }
        DeliveryEventType type = mapType(node);
        if (type == null) {
            return null;
        }
        return new DeliveryEvent(
                messageId,
                text(node, FIELD_EVENT_ID),
                type,
                parseOccurredAt(node),
                bounded(text(node, FIELD_REASON)));
    }

    private DeliveryEventType mapType(JsonNode node) {
        String event = text(node, FIELD_EVENT);
        if (event == null) {
            return null;
        }
        return switch (event.toLowerCase(Locale.ROOT)) {
            case EVENT_DELIVERED -> DeliveryEventType.DELIVERED;
            case EVENT_COMPLAINT -> DeliveryEventType.COMPLAINED;
            case EVENT_BOUNCE -> BOUNCE_HARD.equalsIgnoreCase(text(node, FIELD_BOUNCE_TYPE))
                    ? DeliveryEventType.BOUNCED
                    : DeliveryEventType.FAILED;
            case EVENT_FAILED -> DeliveryEventType.FAILED;
            default -> null;
        };
    }

    private static LocalDateTime parseOccurredAt(JsonNode node) {
        JsonNode timestamp = node.path(FIELD_TIMESTAMP);
        if (timestamp.isIntegralNumber()) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp.asLong()), ZoneOffset.UTC);
        }
        return null;
    }

    private EspResponse send(
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
        return new EspResponse(response.statusCode(), response.body());
    }

    private EspResponse exchange(
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
                .exchange((httpRequest, httpResponse) -> new EspResponse(
                        httpResponse.getStatusCode().value(), readBounded(httpResponse.getBody())));
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new DeliveryProviderException("ESP response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static byte[] hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(body);
        } catch (Exception exception) {
            throw new DeliveryProviderException("Unable to compute the webhook signature");
        }
    }

    private static byte[] decodeHex(String value) {
        int length = value.length();
        if (length == 0 || (length & 1) == 1) {
            return null;
        }
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            out[i / 2] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String text = value.asString(null);
        return text == null || text.isBlank() ? null : text.trim();
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
            throw new IllegalStateException("Delivery ESP " + name + " must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private static int positiveInt(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("Delivery ESP " + name + " must be positive");
        }
        return value;
    }

    private record EspResponse(int statusCode, byte[] body) {
    }
}
