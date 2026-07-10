package ooo.klae.connex.backend.ai.provider.bedrock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/**
 * Minimal Bedrock Runtime transport. The client uses a single Spring {@link RestClient} backed by
 * the JDK {@link HttpClient}, never follows redirects, re-vets the fixed AWS hostname immediately
 * before every send, signs each request with SigV4, bounds response bytes, and retries only once
 * for connection-level failures or Bedrock throttling/service-unavailable responses.
 */
@Component
public class BedrockClient {
    private static final String METHOD_POST = "POST";
    private static final String EMPTY_QUERY = "";
    private static final int BUFFER_BYTES = 8192;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private final RestClient restClient;
    private final int maxResponseBytes;

    public BedrockClient(AiProperties aiProperties) {
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

    /**
     * Invokes Bedrock Runtime InvokeModel for a supported region and model id.
     * @param region supported Bedrock region
     * @param modelId tenant-configured model id
     * @param credentials decrypted AWS credentials
     * @param requestBodyJson Anthropic request body JSON
     * @return provider response body JSON
     */
    public String invokeModel(BedrockRegion region, String modelId, AiCredentials credentials, String requestBodyJson) {
        Objects.requireNonNull(region, "region");
        requireText(modelId, "modelId");
        requireText(requestBodyJson, "requestBodyJson");
        byte[] body = requestBodyJson.getBytes(StandardCharsets.UTF_8);
        String rawPath = "/model/" + modelId + "/invoke";
        String host = region.host();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                BedrockResponse response = sendOnce(host, rawPath, region.regionCode(), credentials, body);
                if (response.statusCode() >= 200 && response.statusCode() <= 299) {
                    return new String(response.body(), StandardCharsets.UTF_8);
                }
                if (attempt == 0 && retryableStatus(response.statusCode())) {
                    continue;
                }
                throw new AiProviderException("Bedrock invocation failed with status " + response.statusCode());
            } catch (ResourceAccessException exception) {
                if (attempt == 0) {
                    continue;
                }
                throw new AiProviderException("Bedrock invocation failed during transport", exception);
            } catch (RestClientException exception) {
                throw new AiProviderException("Bedrock invocation failed during transport", exception);
            }
        }
        throw new AiProviderException("Bedrock invocation failed during transport");
    }

    private BedrockResponse sendOnce(String host, String rawPath, String regionCode, AiCredentials credentials,
            byte[] body) {
        AiEgressGuard.requireFetchable(host);
        AwsSigV4Signer.SignedRequest signed = AwsSigV4Signer.sign(METHOD_POST, host, rawPath, EMPTY_QUERY, body,
                regionCode, credentials, Instant.now());
        URI uri = URI.create("https://" + host + signed.encodedPath());
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Amz-Date", signed.amzDate())
                .header("Authorization", signed.authorization());
        if (signed.securityToken() != null && !signed.securityToken().isBlank()) {
            spec = spec.header("X-Amz-Security-Token", signed.securityToken());
        }
        return spec.body(body)
                .exchange((request, response) -> new BedrockResponse(response.getStatusCode().value(),
                        readBounded(response.getBody())));
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new AiProviderException("Bedrock response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static boolean retryableStatus(int statusCode) {
        return statusCode == HTTP_TOO_MANY_REQUESTS || statusCode == HTTP_SERVICE_UNAVAILABLE;
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
            throw new AiProviderException("Bedrock " + name + " is required");
        }
    }

    private record BedrockResponse(int statusCode, byte[] body) {
    }
}
