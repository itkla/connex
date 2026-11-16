package ooo.klae.connex.backend.exceptions;

/** Refuses departure until a sole named document approver is reassigned. */
public class SoleDocumentApproverException extends ConflictException {
    /** Stable API error code for a departure blocked by a sole named document approval. */
    public static final String CODE = "SOLE_NAMED_DOCUMENT_APPROVER";

    /**
     * Creates the refusal.
     *
     * @param message the refusal's message
     */
    public SoleDocumentApproverException(String message) {
        super(message);
    }

    /** Returns the stable refusal code. */
    public String getCode() {
        return CODE;
    }
}
