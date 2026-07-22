package ooo.klae.connex.backend.delivery;

/**
 * Signals that an outbound delivery provider could not be resolved or used.
 */
public class DeliveryProviderException extends RuntimeException {

    /**
     * Creates the exception with a message.
     * @param message the failure detail
     */
    public DeliveryProviderException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and cause.
     * @param message the failure detail
     * @param cause the underlying cause
     */
    public DeliveryProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
