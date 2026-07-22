package ooo.klae.connex.backend.connectedaccounts;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Default {@link ProviderTokenClient} over {@code java.net.http}. Only ever called with the
 * fixed provider endpoints from {@link ConnectedAccountProviders} — never a caller-supplied
 * URL — with redirects disabled and hard wall-clock timeouts on connect and response.
 */
@Slf4j
@Component
public class HttpProviderTokenClient implements ProviderTokenClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpProviderTokenClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public ProviderTokenResponse exchange(String tokenUri, Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ProviderTokenException("exchange_rejected",
                    "Token endpoint returned status " + response.statusCode());
            }
            JsonNode body;
            try {
                body = objectMapper.readTree(response.body());
            } catch (tools.jackson.core.JacksonException e) {
                throw new ProviderTokenException("exchange_malformed", "Token response is not valid JSON", e);
            }
            String accessToken = textOrNull(body, "access_token");
            if (accessToken == null) {
                throw new ProviderTokenException("exchange_malformed", "Token response missing access_token");
            }
            return new ProviderTokenResponse(
                accessToken,
                textOrNull(body, "refresh_token"),
                body.hasNonNull("expires_in") ? body.get("expires_in").asLong() : null,
                textOrNull(body, "scope"),
                textOrNull(body, "id_token"));
        } catch (IOException e) {
            throw new ProviderTokenException("exchange_unreachable", "Token endpoint unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTokenException("exchange_interrupted", "Token exchange interrupted", e);
        }
    }

    @Override
    public void revoke(String revokeUri, String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(revokeUri))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encode(Map.of("token", token))))
            .build();
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.warn("Provider token revocation failed: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }
}
