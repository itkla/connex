package ooo.klae.connex.backend.ai;

import java.util.Objects;
import java.util.Optional;

/** Structured completion outcome plus an optional bounded masked repair payload. */
public record AiStructuredRepairAttempt<T>(
        AiStructuredOutcome<T> outcome,
        Optional<AiStructuredRepair> repair) {

    public AiStructuredRepairAttempt {
        Objects.requireNonNull(outcome, "outcome");
        repair = Objects.requireNonNull(repair, "repair");
    }
}
