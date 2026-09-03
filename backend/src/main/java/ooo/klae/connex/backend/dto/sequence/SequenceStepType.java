package ooo.klae.connex.backend.dto.sequence;

import java.util.Locale;

/** Closed vocabulary of sequence step behaviors. */
public enum SequenceStepType {
    SEND_EMAIL,
    CALL_TASK,
    GENERAL_TASK,
    WAIT,
    NOTIFY_OWNER;

    /** Returns the lowercase database token for this type. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a lowercase database token. */
    public static SequenceStepType fromToken(String token) {
        if (token == null) {
            throw new IllegalArgumentException("Sequence step type is required");
        }
        return valueOf(token.toUpperCase(Locale.ROOT));
    }
}
