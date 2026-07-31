package ooo.klae.connex.backend.connectedaccounts.capture;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;

/**
 * Redirect-free bounded transport for fixed provider API hosts.
 */
@Component
public class ProviderCaptureHttpClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final ConnectedCaptureProperties properties;
    private final HttpClient httpClient;

    public ProviderCaptureHttpClient(
            ObjectMapper objectMapper, ConnectedCaptureProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getRequestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /** Sends one authorized GET to a caller-validated fixed provider URI. */
    public JsonNode get(URI uri, String accessToken) {
        return get(uri, accessToken, null);
    }

    /** Sends one Microsoft Graph GET with immutable Outlook ids and UTC date projections. */
    public JsonNode getMicrosoft(URI uri, String accessToken, int pageSize) {
        return get(
            uri,
            accessToken,
            "IdType=\"ImmutableId\", outlook.timezone=\"UTC\", "
                + "outlook.body-content-type=\"text\", odata.maxpagesize=" + pageSize);
    }

    private JsonNode get(URI uri, String accessToken, String preference) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(properties.getRequestTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken);
        if (preference != null) {
            builder.header("Prefer", preference);
        }
        HttpRequest request = builder.GET().build();
        try {
            HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new ProviderCaptureException(
                        "response_too_large", false, false, "Provider response exceeded the bound");
                }
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    throw new ProviderCaptureException(
                        "reconnect_required", false, false, "Provider authorization was rejected");
                }
                if (response.statusCode() == 404
                        || response.statusCode() == 410
                        || (response.statusCode() == 400
                            && isDeltaCursorError(objectMapper, body))) {
                    throw new ProviderCaptureException(
                        "cursor_invalid", true, true, "Provider cursor is no longer valid");
                }
                if (response.statusCode() == 429) {
                    throw new ProviderCaptureException(
                        "provider_rate_limited",
                        true,
                        false,
                        retryAfter(response),
                        "Provider rate limit requires a bounded retry");
                }
                if (response.statusCode() >= 500) {
                    throw new ProviderCaptureException(
                        "provider_retryable", true, false, "Provider request should be retried");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ProviderCaptureException(
                        "provider_rejected", false, false, "Provider rejected the capture request");
                }
                return objectMapper.readTree(body);
            }
        } catch (ProviderCaptureException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProviderCaptureException(
                "provider_unreachable", true, false, "Provider request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderCaptureException(
                "provider_interrupted", true, false, "Provider request was interrupted", exception);
        }
    }

    static boolean isDeltaCursorError(ObjectMapper objectMapper, byte[] body) {
        try {
            String code = objectMapper.readTree(body).path("error").path("code").asString();
            return "syncStateNotFound".equalsIgnoreCase(code)
                || "InvalidDeltaToken".equalsIgnoreCase(code);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException exception) {
            try {
                Instant at = ZonedDateTime.parse(
                    value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration duration = Duration.between(Instant.now(), at);
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
