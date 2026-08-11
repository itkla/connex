package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;
import java.util.function.Supplier;

/** Executes one actual provider send through the invocation service's egress boundary. */
@FunctionalInterface
public interface AiProviderAttemptExecutor {
    /** Direct execution for isolated adapter use outside the production invocation choke point. */
    AiProviderAttemptExecutor DIRECT = attempt ->
            Objects.requireNonNull(attempt, "attempt").get();

    /**
     * Executes one provider send.
     * @param attempt deferred provider transport call
     * @return provider response body
     */
    String execute(Supplier<String> attempt);
}
