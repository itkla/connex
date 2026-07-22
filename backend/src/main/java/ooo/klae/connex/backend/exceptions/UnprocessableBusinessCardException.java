package ooo.klae.connex.backend.exceptions;

/**
 * Signals that a business-card image has a supported signature but cannot be safely decoded.
 */
public class UnprocessableBusinessCardException extends RuntimeException {
    public UnprocessableBusinessCardException(String message) {
        super(message);
    }
}
