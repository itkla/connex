package ooo.klae.connex.backend.ai.masking;

/**
 * Raised when the final outbound AI payload still contains a raw identifier from the request-local
 * masking context.
 */
public class MaskingLeakException extends RuntimeException {

    public MaskingLeakException(String message) {
        super(message);
    }
}
