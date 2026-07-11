package ooo.klae.connex.backend.ai.provider.openai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/**
 * Minimal OpenAI-compatible transport. The client never follows redirects, revalidates and
 * re-vets the organization-configured host immediately before every send, conditionally applies
 * bearer authentication, and bounds response bytes.
 */
@Component
public class OpenAiCompatibleClient {
    private static final int BUFFER_BYTES = 8192;

    private final RestClient restClient;
    private final int maxResponseBytes;

    @Autowired
    public OpenAiCompatibleClient(AiProperties aiProperties) {
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

    OpenAiCompatibleClient(RestClient restClient, int maxResponseBytes) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
    }

    /**
     * Sends an OpenAI-compatible chat-completions request.
     * @param endpoint validated organization-configured chat-completions endpoint
     * @param allowInternalEndpoint whether private endpoint addresses and HTTP are permitted
     * @param credentials decrypted optional provider credentials
     * @param requestBodyJson chat-completions request body JSON
     * @return provider response body JSON
     */
    public String complete(URI endpoint, boolean allowInternalEndpoint, AiCredentials credentials,
            String requestBodyJson) {
        String host = requireEndpoint(endpoint, allowInternalEndpoint);
        if (credentials == null) {
            throw new AiProviderException("OpenAI-compatible credentials are required");
        }
        requireText(requestBodyJson, "request body");
        String apiKey = credentials.get("apiKey");
        if (apiKey != null && !apiKey.isBlank()) {
            requireHeaderValue(apiKey);
        }
        byte[] body = requestBodyJson.getBytes(StandardCharsets.UTF_8);
        OpenAiCompatibleResponse response;
        try {
            response = sendOnce(endpoint, host, allowInternalEndpoint, apiKey, body);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        } catch (RuntimeException exception) {
            throw new AiProviderException("OpenAI-compatible invocation failed during transport");
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new AiProviderException(
                    "OpenAI-compatible invocation failed with status " + response.statusCode());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private OpenAiCompatibleResponse sendOnce(URI endpoint, String host, boolean allowInternalEndpoint,
            String apiKey, byte[] body) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey);
        }
        AiEgressGuard.requireFetchableHost(host, allowInternalEndpoint);
        return spec.body(body)
                .exchange((request, response) -> new OpenAiCompatibleResponse(
                        response.getStatusCode().value(), readBounded(response.getBody())));
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new AiProviderException(
                        "OpenAI-compatible response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String requireEndpoint(URI endpoint, boolean allowInternalEndpoint) {
        if (endpoint == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        String scheme = endpoint.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        if ("http".equalsIgnoreCase(scheme) && !allowInternalEndpoint) {
            throw new AiProviderException("Invalid OpenAI-compatible endpoint");
        }
        return host;
    }

    private static void requireHeaderValue(String apiKey) {
        if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new AiProviderException("Invalid OpenAI-compatible credentials");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AiProviderException("OpenAI-compatible " + name + " is required");
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
        return "OpenAiCompatibleClient[redacted]";
    }

    private record OpenAiCompatibleResponse(int statusCode, byte[] body) {
        @Override
        public String toString() {
            return "OpenAiCompatibleResponse[redacted]";
        }
    }
}
