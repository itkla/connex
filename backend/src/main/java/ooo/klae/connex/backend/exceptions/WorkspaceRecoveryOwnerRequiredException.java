package ooo.klae.connex.backend.exceptions;

/** Refuses a change that would remove the workspace's last authority restorer. */
public class WorkspaceRecoveryOwnerRequiredException extends BadRequestException {
    /** Stable API error code for a change that would leave no owner able to restore authority. */
    public static final String CODE = "WORKSPACE_RECOVERY_OWNER_REQUIRED";

    /**
     * Creates the refusal.
     *
     * @param message the refusal's message
     */
    public WorkspaceRecoveryOwnerRequiredException(String message) {
        super(message);
    }

    /** Returns the stable refusal code. */
    public String getCode() {
        return CODE;
    }
}
