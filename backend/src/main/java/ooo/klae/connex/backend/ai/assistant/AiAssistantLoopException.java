package ooo.klae.connex.backend.ai.assistant;

/** Stable fail-closed loop error that never carries provider or CRM content. */
public class AiAssistantLoopException extends RuntimeException {
    private final String terminalReason;
    private final String detailReason;

    public AiAssistantLoopException(String terminalReason, String detailReason) {
        super(detailReason);
        this.terminalReason = terminalReason;
        this.detailReason = detailReason;
    }

    /** @return stable turn terminal reason */
    public String terminalReason() {
        return terminalReason;
    }

    /** @return stable tool or validation detail reason */
    public String detailReason() {
        return detailReason;
    }

    /** Returns a malformed-output failure with a stable internal detail. */
    public static AiAssistantLoopException malformed(String detailReason) {
        return new AiAssistantLoopException("malformed_output", detailReason);
    }

    /** Returns an access-revoked failure with a stable internal detail. */
    public static AiAssistantLoopException accessRevoked(String detailReason) {
        return new AiAssistantLoopException("access_revoked", detailReason);
    }
}
