package ooo.klae.connex.backend.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Supported launch-input value types for schema-v2 workflows. */
public enum WorkflowInputType {
    TEXT,
    USER,
    DATE;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    static WorkflowInputType fromValue(String value) {
        for (WorkflowInputType type : values()) {
            if (type.value().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown workflow input type");
    }
}
