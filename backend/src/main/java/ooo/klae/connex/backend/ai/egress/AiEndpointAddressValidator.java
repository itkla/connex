package ooo.klae.connex.backend.ai.egress;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiProperties;

/** Resolves configured AI endpoint hosts against the runtime egress address policy. */
@Component
public class AiEndpointAddressValidator {
    private static final int MAX_CONCURRENT_VALIDATIONS = 2;

    private final Nat64PrefixPolicy nat64PrefixPolicy;
    private final Duration validationTimeout;
    private final ExecutorService validationExecutor = validationExecutor();
    private final Semaphore validationSlots = new Semaphore(MAX_CONCURRENT_VALIDATIONS, true);

    public AiEndpointAddressValidator(AiProperties aiProperties) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.nat64PrefixPolicy = new Nat64PrefixPolicy(aiProperties.getNat64Prefixes());
        this.validationTimeout = positiveDuration(aiProperties.getConnectTimeoutMs());
    }

    /**
     * Reports within the configured connect deadline whether the host currently resolves entirely
     * within its permitted address class.
     * @param host configured endpoint host
     * @param allowPrivate whether only private endpoint addresses are permitted
     * @return true when the runtime egress guard accepts the current resolution
     */
    public boolean isFetchable(String host, boolean allowPrivate) {
        if (!validationSlots.tryAcquire()) {
            return false;
        }
        Future<InetAddress> resolution;
        try {
            resolution = validationExecutor.submit(() -> {
                try {
                    return resolveFetchable(host, allowPrivate);
                } finally {
                    validationSlots.release();
                }
            });
        } catch (RejectedExecutionException exception) {
            validationSlots.release();
            return false;
        }
        try {
            resolution.get(validationTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    /**
     * Resolves an organization-configured endpoint and returns the validated address to pin.
     * @param host configured endpoint host
     * @param allowPrivate whether only private endpoint addresses are permitted
     * @return first validated address
     */
    public InetAddress resolveFetchable(String host, boolean allowPrivate) {
        return AiEgressGuard.resolveOrgConfiguredHost(host, allowPrivate, nat64PrefixPolicy);
    }

    @PreDestroy
    void shutdown() {
        validationExecutor.shutdownNow();
    }

    private static Duration positiveDuration(long millis) {
        if (millis <= 0) {
            throw new IllegalStateException("AI connect timeout must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private static ExecutorService validationExecutor() {
        return Executors.newFixedThreadPool(
                MAX_CONCURRENT_VALIDATIONS,
                Thread.ofPlatform().daemon().name("ai-endpoint-validator-", 0).factory());
    }
}
