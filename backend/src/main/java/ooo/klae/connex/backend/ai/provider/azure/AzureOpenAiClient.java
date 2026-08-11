package ooo.klae.connex.backend.ai.provider.azure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
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
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;

/**
 * Minimal Azure OpenAI transport. The client uses a single Spring {@link RestClient} backed by the
 * JDK {@link HttpClient}, never follows redirects, re-vets the Azure hostname immediately before
 * every send, and bounds response bytes.
 */
@Component
public class AzureOpenAiClient {
    private static final String AZURE_HOST_SUFFIX = ".openai.azure.com";
    private static final int BUFFER_BYTES = 8192;

    private final RestClient restClient;
    private final int maxResponseBytes;

    @Autowired
    public AzureOpenAiClient(AiProperties aiProperties) {
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

    AzureOpenAiClient(RestClient restClient, int maxResponseBytes) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
    }

    /**
     * Sends an Azure OpenAI chat-completions request.
     * @param endpoint suffix-pinned Azure OpenAI chat-completions endpoint
     * @param credentials decrypted Azure OpenAI credentials
     * @param requestBodyJson chat-completions request body JSON
     * @return provider response body JSON
     */
    public String complete(URI endpoint, AiCredentials credentials, String requestBodyJson) {
        String host = requireAzureEndpoint(endpoint);
        if (credentials == null) {
            throw new AiProviderException("Azure OpenAI credentials are required");
        }
        requireText(requestBodyJson, "request body");
        String apiKey = credentials.require("apiKey");
        byte[] body = requestBodyJson.getBytes(StandardCharsets.UTF_8);
        AzureOpenAiResponse response;
        try {
            response = sendOnce(endpoint, host, apiKey, body);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiProviderException("Azure OpenAI invocation failed during transport");
        } catch (RuntimeException exception) {
            throw new AiProviderException("Azure OpenAI invocation failed during transport");
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new AiProviderRequestRejectedException("Azure OpenAI", response.statusCode());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private AzureOpenAiResponse sendOnce(URI endpoint, String host, String apiKey, byte[] body) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("api-key", apiKey);
        AiEgressGuard.requireFetchableHost(host, false);
        return spec.body(body)
                .exchange((request, response) -> new AzureOpenAiResponse(response.getStatusCode().value(),
                        readBounded(response.getBody())));
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new AiProviderException("Azure OpenAI response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String requireAzureEndpoint(URI endpoint) {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getPort() != -1) {
            throw new AiProviderException("Invalid Azure OpenAI endpoint");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new AiProviderException("Invalid Azure OpenAI endpoint");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!normalizedHost.endsWith(AZURE_HOST_SUFFIX)) {
            throw new AiProviderException("Invalid Azure OpenAI endpoint");
        }
        return normalizedHost;
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AiProviderException("Azure OpenAI " + name + " is required");
        }
    }

    private record AzureOpenAiResponse(int statusCode, byte[] body) {
    }
}
