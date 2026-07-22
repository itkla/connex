package ooo.klae.connex.backend.exceptions;

/**
 * Indicates that a completed business-card import no longer has a readable result.
 */
public class BusinessCardImportResultGoneException extends RuntimeException {
    public BusinessCardImportResultGoneException(String message) {
        super(message);
    }
}
