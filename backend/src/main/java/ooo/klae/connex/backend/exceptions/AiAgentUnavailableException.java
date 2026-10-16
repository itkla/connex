package ooo.klae.connex.backend.exceptions;

/** Stable fail-closed mission admission response without exposing deployment configuration. */
public class AiAgentUnavailableException extends ServiceUnavailableException {
    public AiAgentUnavailableException() {
        super("mission_unavailable");
    }
}
