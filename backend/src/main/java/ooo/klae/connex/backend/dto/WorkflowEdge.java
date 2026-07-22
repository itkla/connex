package ooo.klae.connex.backend.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A directed graph edge with one of the schema-v1 lowercase outcomes. */
public record WorkflowEdge(
    String id,
    String sourceNodeId,
    String targetNodeId,
    Outcome outcome
) {

    /** Schema-v1 transition outcomes. */
    public enum Outcome {
        NEXT,
        YES,
        NO;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        static Outcome fromValue(String value) {
            for (Outcome outcome : values()) {
                if (outcome.value().equals(value)) {
                    return outcome;
                }
            }
            throw new IllegalArgumentException("Unknown workflow edge outcome");
        }
    }
}
