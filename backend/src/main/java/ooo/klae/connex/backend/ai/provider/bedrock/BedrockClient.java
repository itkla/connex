package ooo.klae.connex.backend.ai.provider.bedrock;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.hc.core5.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.egress.FixedAiProviderClient;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/**
 * Minimal Bedrock Runtime transport. Every attempt re-resolves and pins the closed AWS host,
 * re-signs with SigV4, and shares one hard deadline across bounded status retries.
 */
@Component
public class BedrockClient {
    private static final String METHOD_POST = "POST";
    private static final String EMPTY_QUERY = "";
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final int MAX_ATTEMPTS = 2;
    private static final long MAX_RETRY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long TRANSIENT_RETRY_BASE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long THROTTLED_RETRY_BASE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Set<String> ALLOWED_HOSTS = Arrays.stream(BedrockRegion.values())
            .map(BedrockRegion::host)
            .collect(Collectors.toUnmodifiableSet());

    private final FixedAiProviderClient providerClient;
    private final long requestTimeoutMillis;
    private final RetrySleeper retrySleeper;
    private final JitterSampler jitterSampler;

    @Autowired
    public BedrockClient(AiProperties aiProperties, FixedAiProviderClient providerClient) {
        this(
                aiProperties,
                providerClient,
                TimeUnit.NANOSECONDS::sleep,
                maximum -> ThreadLocalRandom.current().nextLong(maximum + 1));
    }

    BedrockClient(
            AiProperties aiProperties,
            FixedAiProviderClient providerClient,
            RetrySleeper retrySleeper,
            JitterSampler jitterSampler) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.providerClient = Objects.requireNonNull(providerClient, "providerClient");
        this.requestTimeoutMillis = positiveLong(aiProperties.getRequestTimeoutMs(), "request timeout");
        this.retrySleeper = Objects.requireNonNull(retrySleeper, "retrySleeper");
        this.jitterSampler = Objects.requireNonNull(jitterSampler, "jitterSampler");
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
        AiRequestDeadline deadline = AiRequestDeadline.afterMillis(requestTimeoutMillis);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            BedrockResponse response;
            try {
                response = sendOnce(
                        host, rawPath, region.regionCode(), credentials, body, deadline);
            } catch (FixedAiProviderClient.RetryableTransportException exception) {
                if (canRetry(attempt)
                        && pauseBeforeRetry(TRANSIENT_RETRY_BASE_NANOS, attempt, deadline)) {
                    continue;
                }
                throw exception;
            }
            if (response.statusCode() >= 200 && response.statusCode() <= 299) {
                return new String(response.body(), StandardCharsets.UTF_8);
            }
            long retryBaseNanos = retryBaseNanos(response.statusCode());
            if (canRetry(attempt) && retryBaseNanos > 0
                    && pauseBeforeRetry(retryBaseNanos, attempt, deadline)) {
                continue;
            }
            throw new AiProviderException("Bedrock invocation failed with status " + response.statusCode());
        }
        throw new AiProviderException("Bedrock invocation failed during transport");
    }

    private BedrockResponse sendOnce(String host, String rawPath, String regionCode, AiCredentials credentials,
            byte[] body, AiRequestDeadline deadline) {
        AwsSigV4Signer.SignedRequest signed = AwsSigV4Signer.sign(METHOD_POST, host, rawPath, EMPTY_QUERY, body,
                regionCode, credentials, Instant.now());
        URI uri = URI.create("https://" + host + signed.encodedPath());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
        headers.put("Accept", ContentType.APPLICATION_JSON.getMimeType());
        headers.put("X-Amz-Date", signed.amzDate());
        headers.put("Authorization", signed.authorization());
        if (signed.securityToken() != null && !signed.securityToken().isBlank()) {
            headers.put("X-Amz-Security-Token", signed.securityToken());
        }
        FixedAiProviderClient.Response response = providerClient.post(
                uri, ALLOWED_HOSTS, headers, ContentType.APPLICATION_JSON, body, deadline, "Bedrock invocation");
        return new BedrockResponse(response.statusCode(), response.body());
    }

    private boolean pauseBeforeRetry(long baseNanos, int retryOrdinal, AiRequestDeadline deadline) {
        long remainingNanos = deadline.remainingNanos();
        if (remainingNanos <= 0) {
            return false;
        }
        int exponent = Math.min(retryOrdinal, 20);
        long retryWindowNanos = Math.min(MAX_RETRY_WINDOW_NANOS, baseNanos << exponent);
        long delayNanos = jitterSampler.sample(retryWindowNanos);
        if (delayNanos < 0 || delayNanos > retryWindowNanos) {
            throw new IllegalStateException("Bedrock retry jitter is outside its configured window");
        }
        if (delayNanos >= remainingNanos) {
            return false;
        }
        try {
            retrySleeper.sleepNanos(delayNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Bedrock invocation was interrupted");
        }
        return !deadline.isExpired();
    }

    private static boolean canRetry(int attempt) {
        return attempt + 1 < MAX_ATTEMPTS;
    }

    private static long retryBaseNanos(int statusCode) {
        return switch (statusCode) {
            case HTTP_INTERNAL_SERVER_ERROR, HTTP_SERVICE_UNAVAILABLE -> TRANSIENT_RETRY_BASE_NANOS;
            case HTTP_TOO_MANY_REQUESTS -> THROTTLED_RETRY_BASE_NANOS;
            default -> 0;
        };
    }

    private static long positiveLong(long millis, String name) {
        if (millis <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return millis;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AiProviderException("Bedrock " + name + " is required");
        }
    }

    private record BedrockResponse(int statusCode, byte[] body) {
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleepNanos(long delayNanos) throws InterruptedException;
    }

    @FunctionalInterface
    interface JitterSampler {
        long sample(long maximumInclusive);
    }
}
