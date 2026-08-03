package ooo.klae.connex.backend.services;

/** Fixed-code canonical runtime failure safe for persisted diagnostics. */
public class WorkflowExecutionException extends RuntimeException {

    private final String code;
    private final String safeMessage;
    private final boolean interventionRequired;

    public WorkflowExecutionException(
            String code, String safeMessage, boolean interventionRequired) {
        super(safeMessage);
        this.code = code;
        this.safeMessage = safeMessage;
        this.interventionRequired = interventionRequired;
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public boolean interventionRequired() {
        return interventionRequired;
    }
}
