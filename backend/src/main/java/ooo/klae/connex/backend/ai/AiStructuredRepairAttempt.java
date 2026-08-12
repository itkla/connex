package ooo.klae.connex.backend.ai;

import java.util.Objects;
import java.util.Optional;

/** Structured completion outcome plus an optional bounded masked repair payload. */
public record AiStructuredRepairAttempt<T>(
        AiStructuredOutcome<T> outcome,
        Optional<AiStructuredRepair> repair,
        Optional<String> reasoning) {

    public AiStructuredRepairAttempt(
            AiStructuredOutcome<T> outcome,
            Optional<AiStructuredRepair> repair) {
        this(outcome, repair, Optional.empty());
    }

    public AiStructuredRepairAttempt {
        Objects.requireNonNull(outcome, "outcome");
        repair = Objects.requireNonNull(repair, "repair");
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
    }

    @Override
    public String toString() {
        return "AiStructuredRepairAttempt[outcome=" + outcome
                + ", repair=" + (repair.isPresent() ? "present" : "empty")
                + ", reasoning=<redacted>]";
    }
}
