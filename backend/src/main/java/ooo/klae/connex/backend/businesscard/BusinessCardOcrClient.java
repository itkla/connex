package ooo.klae.connex.backend.businesscard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.exceptions.UnprocessableBusinessCardException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated, bounded client for the private one-worker PaddleOCR sidecar.
 */
@Component
public class BusinessCardOcrClient {
    private static final int MAX_LINES = 256;
    private static final int MAX_LINE_CHARACTERS = 512;
    private static final int BUFFER_BYTES = 8_192;
    private static final int MIN_TOKEN_CHARACTERS = 32;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final BusinessCardProperties properties;
    private final URI scanEndpoint;
    private final URI healthEndpoint;
    private final Semaphore invocation = new Semaphore(1);

    private volatile long readinessExpiresAtNanos;
    private volatile boolean cachedReady;

    @Autowired
    public BusinessCardOcrClient(BusinessCardProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(positive(properties.getConnectTimeout(), "OCR connect timeout"))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(positive(properties.getRequestTimeout(), "OCR request timeout"));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        URI base = validBase(properties.getOcrBaseUrl());
        this.scanEndpoint = base == null ? null : URI.create(base.toString() + "/v1/ocr");
        this.healthEndpoint = base == null ? null : URI.create(base.toString() + "/health");
    }

    BusinessCardOcrClient(RestClient restClient, ObjectMapper objectMapper,
            BusinessCardProperties properties, URI base) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        URI normalized = validBase(base);
        this.scanEndpoint = normalized == null ? null : URI.create(normalized.toString() + "/v1/ocr");
        this.healthEndpoint = normalized == null ? null : URI.create(normalized.toString() + "/health");
    }

    /**
     * Returns cached sidecar readiness without exposing its private endpoint.
     *
     * @return {@code true} when configured and healthy
     */
    public boolean isReady() {
        if (!isConfigured()) {
            return false;
        }
        long now = System.nanoTime();
        if (now < readinessExpiresAtNanos) {
            return cachedReady;
        }
        synchronized (this) {
            now = System.nanoTime();
            if (now < readinessExpiresAtNanos) {
                return cachedReady;
            }
            cachedReady = checkHealth();
            readinessExpiresAtNanos = now + positive(properties.getReadinessCache(), "OCR readiness cache").toNanos();
            return cachedReady;
        }
    }

    /**
     * Runs one bounded OCR invocation and rejects excess concurrent work.
     *
     * @param image validated card image
     * @return recognized lines only, without retaining raw OCR output
     */
    public List<OcrLine> recognize(ValidatedBusinessCardImage image) {
        Objects.requireNonNull(image, "image");
        if (!isConfigured()) {
            throw unavailable();
        }
        if (!invocation.tryAcquire()) {
            throw new TooManyRequestsException("Business-card scanning is busy; retry shortly");
        }
        try {
            OcrResponse response = restClient.post()
                    .uri(scanEndpoint)
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getOcrServiceToken())
                    .body(image.content())
                    .exchange((request, result) -> new OcrResponse(
                            result.getStatusCode().value(), readBounded(result.getBody())));
            return handle(response);
        } catch (TooManyRequestsException | RequestBodyTooLargeException
                | UnprocessableBusinessCardException | ServiceUnavailableException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        } finally {
            invocation.release();
        }
    }

    private boolean isConfigured() {
        String token = properties.getOcrServiceToken();
        return properties.isEnabled()
                && scanEndpoint != null
                && token != null
                && token.length() >= MIN_TOKEN_CHARACTERS
                && token.indexOf('\r') < 0
                && token.indexOf('\n') < 0
                && properties.getMaxResponseBytes() > 0;
    }

    private boolean checkHealth() {
        try {
            OcrResponse response = restClient.get()
                    .uri(healthEndpoint)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, result) -> new OcrResponse(
                            result.getStatusCode().value(), readBounded(result.getBody())));
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode root = objectMapper.readTree(response.body());
            return root != null && root.path("ready").asBoolean(false);
        } catch (Exception exception) {
            return false;
        }
    }

    private List<OcrLine> handle(OcrResponse response) throws IOException {
        if (response.statusCode() == 429) {
            throw new TooManyRequestsException("Business-card scanning is busy; retry shortly");
        }
        if (response.statusCode() == 413) {
            throw new RequestBodyTooLargeException(properties.getMaxImageBytes());
        }
        if (response.statusCode() == 415 || response.statusCode() == 422) {
            throw new UnprocessableBusinessCardException("Business-card image could not be processed");
        }
        if (response.statusCode() != 200) {
            throw unavailable();
        }
        return parseLines(response.body());
    }

    private List<OcrLine> parseLines(byte[] body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode lines = root == null ? null : root.get("lines");
        if (lines == null || !lines.isArray() || lines.size() > MAX_LINES) {
            throw unavailable();
        }
        List<OcrLine> parsed = new ArrayList<>(lines.size());
        for (JsonNode line : lines) {
            String text = requiredText(line == null ? null : line.get("text"));
            double confidence = requiredConfidence(line.get("confidence"));
            JsonNode box = line.get("box");
            if (box == null || !box.isArray() || box.size() != 4) {
                throw unavailable();
            }
            int xMin = coordinate(box.get(0));
            int yMin = coordinate(box.get(1));
            int xMax = coordinate(box.get(2));
            int yMax = coordinate(box.get(3));
            if (xMax < xMin || yMax < yMin) {
                throw unavailable();
            }
            parsed.add(new OcrLine(text, confidence, xMin, yMin, xMax, yMax));
        }
        return List.copyOf(parsed);
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > properties.getMaxResponseBytes() - total) {
                throw unavailable();
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String requiredText(JsonNode node) {
        if (node == null || !node.isString()) {
            throw unavailable();
        }
        String text = node.asText();
        if (text.isBlank() || text.length() > MAX_LINE_CHARACTERS
                || text.chars().anyMatch(value -> value == 0)) {
            throw unavailable();
        }
        return text;
    }

    private static double requiredConfidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw unavailable();
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw unavailable();
        }
        return value;
    }

    private static int coordinate(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw unavailable();
        }
        int value = node.asInt();
        if (value < 0) {
            throw unavailable();
        }
        return value;
    }

    private static URI validBase(URI candidate) {
        if (candidate == null) {
            return null;
        }
        String scheme = candidate.getScheme();
        String path = candidate.getPath();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || candidate.getHost() == null
                || candidate.getHost().isBlank()
                || candidate.getUserInfo() != null
                || candidate.getQuery() != null
                || candidate.getFragment() != null
                || (path != null && !path.isBlank() && !path.equals("/"))) {
            return null;
        }
        String authority = candidate.getRawAuthority().toLowerCase(Locale.ROOT);
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + authority);
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
        return value;
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("Business-card scanning is unavailable");
    }

    @Override
    public String toString() {
        return "BusinessCardOcrClient[redacted]";
    }

    private record OcrResponse(int statusCode, byte[] body) {
        @Override
        public String toString() {
            return "OcrResponse[redacted]";
        }
    }
}
