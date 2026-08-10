package ooo.klae.connex.backend.ai;

import java.util.Objects;

/** Terminal result returned by one feature adapter to the shared generation boundary. */
public record AiGenerationTaskResult<T>(
        Outcome outcome,
        T result,
        String reason,
        boolean sensitive) {

    /** Generation task terminal classification. */
    public enum Outcome {
        RESOLVED,
        FAILED,
        TIMED_OUT
    }

    public AiGenerationTaskResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.RESOLVED) {
            Objects.requireNonNull(result, "resolved result");
            if (reason != null) {
                throw new IllegalArgumentException("Resolved AI generation must not carry a failure reason");
            }
        } else {
            if (result != null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Unresolved AI generation requires only a stable reason");
            }
            sensitive = false;
        }
    }

    /** Returns a resolved result whose disclosure must retain the live AI gate. */
    public static <T> AiGenerationTaskResult<T> resolved(T result) {
        return new AiGenerationTaskResult<>(Outcome.RESOLVED, result, null, true);
    }

    /** Returns a resolved domain-unavailable result that contains no generated content. */
    public static <T> AiGenerationTaskResult<T> unavailable(T result) {
        return new AiGenerationTaskResult<>(Outcome.RESOLVED, result, null, false);
    }

    /** Returns a failed generation with a stable reason. */
    public static <T> AiGenerationTaskResult<T> failed(String reason) {
        return new AiGenerationTaskResult<>(Outcome.FAILED, null, reason, false);
    }

    /** Returns a timed-out generation with a stable reason. */
    public static <T> AiGenerationTaskResult<T> timedOut(String reason) {
        return new AiGenerationTaskResult<>(Outcome.TIMED_OUT, null, reason, false);
    }
}
