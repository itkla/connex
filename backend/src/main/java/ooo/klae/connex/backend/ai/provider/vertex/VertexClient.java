package ooo.klae.connex.backend.ai.provider.vertex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/**
 * Minimal Vertex AI transport. The client accepts only constructed regional Vertex hosts, never
 * follows redirects, re-vets the host immediately before sending, and bounds response bytes.
 */
@Component
public class VertexClient {
    private static final Pattern VERTEX_HOST = Pattern.compile(
            "^[a-z]+-[a-z]+[0-9]{1,2}-aiplatform\\.googleapis\\.com$");
    private static final int BUFFER_BYTES = 8192;

    private final RestClient restClient;
    private final int maxResponseBytes;

    @Autowired
    public VertexClient(AiProperties aiProperties) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(duration(aiProperties.getConnectTimeoutMs(), "connect timeout"))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(duration(aiProperties.getRequestTimeoutMs(), "request timeout"));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.maxResponseBytes = positiveInt(aiProperties.getMaxResponseBytes(), "max response bytes");
    }

    VertexClient(RestClient restClient, int maxResponseBytes) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
    }

    /**
     * Sends a Vertex AI model request.
     * @param endpoint constructed regional Vertex endpoint
     * @param accessToken short-lived Google OAuth access token
     * @param requestBodyJson provider request JSON
     * @return provider response JSON
     */
    public String complete(URI endpoint, String accessToken, String requestBodyJson) {
        String host = requireVertexEndpoint(endpoint);
        requireHeaderValue(accessToken);
        requireText(requestBodyJson, "request body");
        byte[] body = requestBodyJson.getBytes(StandardCharsets.UTF_8);
        VertexResponse response;
        try {
            response = sendOnce(endpoint, host, accessToken, body);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiProviderException("Vertex invocation failed during transport");
        } catch (RuntimeException exception) {
            throw new AiProviderException("Vertex invocation failed during transport");
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new AiProviderException("Vertex invocation failed with status " + response.statusCode());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private VertexResponse sendOnce(URI endpoint, String host, String accessToken, byte[] body) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken);
        AiEgressGuard.requireFetchableHost(host, false);
        return spec.body(body)
                .exchange((request, response) -> new VertexResponse(response.getStatusCode().value(),
                        readBounded(response.getBody())));
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new AiProviderException("Vertex response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String requireVertexEndpoint(URI endpoint) {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || endpoint.getQuery() != null || endpoint.getPort() != -1) {
            throw new AiProviderException("Invalid Vertex endpoint");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new AiProviderException("Invalid Vertex endpoint");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!VERTEX_HOST.matcher(normalizedHost).matches()) {
            throw new AiProviderException("Invalid Vertex endpoint");
        }
        return normalizedHost;
    }

    private static void requireHeaderValue(String accessToken) {
        if (accessToken == null || accessToken.isBlank()
                || accessToken.indexOf('\r') >= 0 || accessToken.indexOf('\n') >= 0) {
            throw new AiProviderException("Vertex access token is required");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AiProviderException("Vertex " + name + " is required");
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

    @Override
    public String toString() {
        return "VertexClient[redacted]";
    }

    private record VertexResponse(int statusCode, byte[] body) {
        @Override
        public String toString() {
            return "VertexResponse[redacted]";
        }
    }
}
