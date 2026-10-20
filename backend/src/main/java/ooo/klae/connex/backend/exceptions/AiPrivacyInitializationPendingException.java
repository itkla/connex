package ooo.klae.connex.backend.exceptions;

/** Refuses new chat admission until durable workspace privacy reconciliation succeeds. */
public class AiPrivacyInitializationPendingException extends ServiceUnavailableException {
    public AiPrivacyInitializationPendingException() {
        super("ai_privacy_initialization_pending");
    }
}
