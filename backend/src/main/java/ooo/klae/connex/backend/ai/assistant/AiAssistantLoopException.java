package ooo.klae.connex.backend.ai.assistant;

/** Stable fail-closed loop error that never carries provider or CRM content. */
public class AiAssistantLoopException extends RuntimeException {
    private final String terminalReason;
    private final String detailReason;
    private final boolean recoverable;

    public AiAssistantLoopException(String terminalReason, String detailReason) {
        this(terminalReason, detailReason, false);
    }

    private AiAssistantLoopException(
            String terminalReason, String detailReason, boolean recoverable) {
        super(detailReason);
        this.terminalReason = terminalReason;
        this.detailReason = detailReason;
        this.recoverable = recoverable;
    }

    /** @return stable turn terminal reason */
    public String terminalReason() {
        return terminalReason;
    }

    /** @return stable tool or validation detail reason */
    public String detailReason() {
        return detailReason;
    }

    /**
     * Whether the agent loop may return this refusal to the model as a correctable tool error.
     *
     * <p>Only a refusal of model-proposed arguments is recoverable: the server computed nothing,
     * exposed nothing, and a corrected proposal could succeed. Refusals that reflect turn state —
     * revoked access, exhausted budgets, a changed saved view — stay non-recoverable because no
     * argument change can make them succeed.
     *
     * @return true when the refusal may be surfaced to the model instead of ending the turn
     */
    public boolean recoverable() {
        return recoverable;
    }

    /** Returns a malformed-output failure with a stable internal detail. */
    public static AiAssistantLoopException malformed(String detailReason) {
        return new AiAssistantLoopException("malformed_output", detailReason);
    }

    /**
     * Returns a recoverable refusal of model-proposed tool arguments.
     *
     * @param detailReason stable argument-refusal detail the model may act on
     * @return refusal the loop may feed back as an error tool result
     */
    public static AiAssistantLoopException refusedArguments(String detailReason) {
        return new AiAssistantLoopException("malformed_output", detailReason, true);
    }

    /** Returns an access-revoked failure with a stable internal detail. */
    public static AiAssistantLoopException accessRevoked(String detailReason) {
        return new AiAssistantLoopException("access_revoked", detailReason);
    }

    /** Returns a turn-deadline failure with a stable internal detail. */
    public static AiAssistantLoopException deadlineExceeded() {
        return new AiAssistantLoopException("turn_deadline_exceeded", "turn_deadline_exceeded");
    }
}
