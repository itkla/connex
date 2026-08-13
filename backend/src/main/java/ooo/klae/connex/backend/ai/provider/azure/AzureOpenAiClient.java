package ooo.klae.connex.backend.ai.provider.azure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.hc.core5.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.egress.FixedAiProviderClient;
import ooo.klae.connex.backend.ai.egress.AiSseEventReader;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiSseAccumulator;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;

/**
 * Minimal Azure OpenAI transport. Production requests use the shared pinned fixed-provider client
 * so DNS validation and the HTTP exchange consume one absolute deadline. The package-local
 * {@link RestClient} path keeps isolated request-shape tests lightweight.
 */
@Component
public class AzureOpenAiClient {
    private static final String AZURE_HOST_SUFFIX = ".openai.azure.com";
    private static final int BUFFER_BYTES = 8192;

    private final RestClient restClient;
    private final FixedAiProviderClient providerClient;
    private final int maxResponseBytes;
    private final long requestTimeoutMillis;

    @Autowired
    public AzureOpenAiClient(AiProperties aiProperties, FixedAiProviderClient providerClient) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.restClient = null;
        this.providerClient = Objects.requireNonNull(providerClient, "providerClient");
        this.maxResponseBytes = positiveInt(aiProperties.getMaxResponseBytes(), "max response bytes");
        this.requestTimeoutMillis = positiveLong(aiProperties.getRequestTimeoutMs(), "request timeout");
    }

    AzureOpenAiClient(RestClient restClient, int maxResponseBytes) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.providerClient = null;
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.requestTimeoutMillis = 60_000;
    }

    /**
     * Sends an Azure OpenAI chat-completions request.
     * @param endpoint suffix-pinned Azure OpenAI chat-completions endpoint
     * @param credentials decrypted Azure OpenAI credentials
     * @param requestBodyJson chat-completions request body JSON
     * @return provider response body JSON
     */
    public String complete(URI endpoint, AiCredentials credentials, String requestBodyJson) {
        return complete(endpoint, credentials, requestBodyJson,
                AiRequestDeadline.afterMillis(requestTimeoutMillis));
    }

    String complete(
            URI endpoint,
            AiCredentials credentials,
            String requestBodyJson,
            AiRequestDeadline deadline) {
        String host = requireAzureEndpoint(endpoint);
        if (credentials == null) {
            throw new AiProviderException("Azure OpenAI credentials are required");
        }
        requireText(requestBodyJson, "request body");
        Objects.requireNonNull(deadline, "deadline");
        String apiKey = credentials.require("apiKey");
        byte[] body = requestBodyJson.getBytes(StandardCharsets.UTF_8);
        AzureOpenAiResponse response;
        try {
            response = sendOnce(endpoint, host, apiKey, body, deadline);
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

    /** Streams and normalizes one Azure OpenAI chat completion. */
    public AiCompletionResult stream(
            URI endpoint,
            AiCredentials credentials,
            String requestBodyJson,
            AiRequestDeadline deadline,
            OpenAiSseAccumulator accumulator,
            AiProviderStreamObserver observer) {
        String host = requireAzureEndpoint(endpoint);
        if (credentials == null) {
            throw new AiProviderException("Azure OpenAI credentials are required");
        }
        requireText(requestBodyJson, "request body");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(accumulator, "accumulator");
        String apiKey = credentials.require("apiKey");
        byte[] body = requestBodyJson.getBytes(StandardCharsets.UTF_8);
        if (providerClient != null) {
            FixedAiProviderClient.StreamResponse<AiCompletionResult> response =
                    providerClient.postStream(
                            endpoint,
                            Set.of(host),
                            Map.of(
                                    "Content-Type", ContentType.APPLICATION_JSON.getMimeType(),
                                    "Accept", MediaType.TEXT_EVENT_STREAM_VALUE,
                                    "api-key", apiKey),
                            ContentType.APPLICATION_JSON,
                            body,
                            deadline,
                            "Azure OpenAI invocation",
                            observer,
                            input -> {
                                AiSseEventReader.read(
                                        input, accumulator::accept,
                                        accumulator::onTransportActivity);
                                return accumulator.finish();
                            });
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new AiProviderRequestRejectedException(
                        "Azure OpenAI", response.statusCode());
            }
            return Objects.requireNonNull(response.value(), "Azure streaming response");
        }
        requireRemainingDeadline(deadline);
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("api-key", apiKey);
        AiEgressGuard.requireFetchableHost(host, false);
        AiCompletionResult result = spec.body(body).exchange((request, response) -> {
            if (response.getStatusCode().isError()) {
                throw new AiProviderRequestRejectedException(
                        "Azure OpenAI", response.getStatusCode().value());
            }
            AiSseEventReader.read(
                    response.getBody(), accumulator::accept,
                    accumulator::onTransportActivity);
            return accumulator.finish();
        });
        requireRemainingDeadline(deadline);
        return result;
    }

    private AzureOpenAiResponse sendOnce(
            URI endpoint,
            String host,
            String apiKey,
            byte[] body,
            AiRequestDeadline deadline) {
        if (providerClient != null) {
            FixedAiProviderClient.Response response = providerClient.post(
                    endpoint,
                    Set.of(host),
                    Map.of(
                            "Content-Type", ContentType.APPLICATION_JSON.getMimeType(),
                            "Accept", ContentType.APPLICATION_JSON.getMimeType(),
                            "api-key", apiKey),
                    ContentType.APPLICATION_JSON,
                    body,
                    deadline,
                    "Azure OpenAI invocation");
            return new AzureOpenAiResponse(response.statusCode(), response.body());
        }
        requireRemainingDeadline(deadline);
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("api-key", apiKey);
        AiEgressGuard.requireFetchableHost(host, false);
        AzureOpenAiResponse response = spec.body(body)
                .exchange((request, providerResponse) -> new AzureOpenAiResponse(
                        providerResponse.getStatusCode().value(),
                        readBounded(providerResponse.getBody())));
        requireRemainingDeadline(deadline);
        return response;
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

    private static long positiveLong(long value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return value;
    }

    private static void requireRemainingDeadline(AiRequestDeadline deadline) {
        if (deadline.isExpired()) {
            throw new AiProviderException("Azure OpenAI invocation exceeded its deadline");
        }
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
