package ooo.klae.connex.backend.ai.provider.openai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.ai.egress.PinnedHostDnsResolver;
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
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final AiEndpointAddressValidator endpointAddressValidator;

    @Autowired
    public OpenAiCompatibleClient(
            AiProperties aiProperties, AiEndpointAddressValidator endpointAddressValidator) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.restClient = null;
        this.connectTimeout = duration(aiProperties.getConnectTimeoutMs(), "connect timeout");
        this.requestTimeout = duration(aiProperties.getRequestTimeoutMs(), "request timeout");
        this.maxResponseBytes = positiveInt(aiProperties.getMaxResponseBytes(), "max response bytes");
        this.endpointAddressValidator = Objects.requireNonNull(endpointAddressValidator, "endpointAddressValidator");
    }

    OpenAiCompatibleClient(RestClient restClient, int maxResponseBytes) {
        this(restClient, maxResponseBytes, new AiEndpointAddressValidator(new AiProperties()));
    }

    OpenAiCompatibleClient(
            RestClient restClient,
            int maxResponseBytes,
            AiEndpointAddressValidator endpointAddressValidator) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.connectTimeout = null;
        this.requestTimeout = null;
        this.endpointAddressValidator = Objects.requireNonNull(endpointAddressValidator, "endpointAddressValidator");
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
            InetAddress pinnedAddress = endpointAddressValidator.resolveFetchable(host, allowInternalEndpoint);
            if (restClient != null) {
                response = sendOnce(restClient, endpoint, apiKey, body);
            } else {
                try (PinnedRestClient pinned = pinnedRestClient(host, pinnedAddress)) {
                    response = sendOnce(pinned.restClient(), endpoint, apiKey, body);
                }
            }
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

    private OpenAiCompatibleResponse sendOnce(
            RestClient client, URI endpoint, String apiKey, byte[] body) {
        RestClient.RequestBodySpec spec = client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey);
        }
        return spec.body(body)
                .exchange((request, response) -> new OpenAiCompatibleResponse(
                        response.getStatusCode().value(), readBounded(response.getBody())));
    }

    private PinnedRestClient pinnedRestClient(String host, InetAddress address) {
        Timeout connect = Timeout.of(connectTimeout);
        Timeout request = Timeout.of(requestTimeout);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(connect)
            .setSocketTimeout(request)
            .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new PinnedHostDnsResolver(host, address))
            .setDefaultConnectionConfig(connectionConfig)
            .setMaxConnPerRoute(1)
            .setMaxConnTotal(1)
            .build();
        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build();
        HttpComponentsClientHttpRequestFactory requestFactory =
            new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectionRequestTimeout(connectTimeout);
        requestFactory.setReadTimeout(requestTimeout);
        RestClient pinned = RestClient.builder().requestFactory(requestFactory).build();
        return new PinnedRestClient(pinned, httpClient);
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

    private record PinnedRestClient(RestClient restClient, CloseableHttpClient httpClient)
            implements AutoCloseable {
        @Override
        public void close() {
            httpClient.close(CloseMode.GRACEFUL);
        }
    }
}
