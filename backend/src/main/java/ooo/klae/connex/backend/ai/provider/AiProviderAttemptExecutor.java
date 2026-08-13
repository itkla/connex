package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;
import java.util.function.Supplier;

import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;

/** Executes one actual provider send through the invocation service's egress boundary. */
@FunctionalInterface
public interface AiProviderAttemptExecutor {
    /** Direct execution for isolated adapter use outside the production invocation choke point. */
    AiProviderAttemptExecutor DIRECT = attempt ->
            Objects.requireNonNull(attempt, "attempt").get();

    /**
     * Creates the monotonic deadline shared by every network step and fallback in this invocation.
     * @param requestTimeoutMillis configured provider request timeout
     * @return provider deadline, additionally bounded by any caller-owned budget
     */
    default AiRequestDeadline deadline(long requestTimeoutMillis) {
        return AiRequestDeadline.afterMillis(requestTimeoutMillis);
    }

    /**
     * Executes one provider send.
     * @param attempt deferred provider transport call
     * @return provider response body
     */
    String execute(Supplier<String> attempt);
}
