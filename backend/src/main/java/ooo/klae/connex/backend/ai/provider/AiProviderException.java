package ooo.klae.connex.backend.ai.provider;

/**
 * Fail-closed provider adaptation or transport failure. The invocation layer maps this exception
 * to a generic AI-unavailable outcome and must not expose provider secrets or raw bodies.
 */
public class AiProviderException extends RuntimeException {

    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
