package ooo.klae.connex.backend.exceptions;

/** Refuses a change that would remove the last active workspace owner. */
public class WorkspaceOwnerRequiredException extends BadRequestException {
    /** Stable API error code for a change that would leave the workspace without an active owner. */
    public static final String CODE = "WORKSPACE_LAST_OWNER";

    /**
     * Creates the refusal.
     *
     * @param message the refusal's message
     */
    public WorkspaceOwnerRequiredException(String message) {
        super(message);
    }

    /** Returns the stable refusal code. */
    public String getCode() {
        return CODE;
    }
}
