package ooo.klae.connex.backend.exceptions;

/**
 * Raised when an operation depends on outbound email but the instance has no usable transport.
 * Surfaced rather than swallowed so an operator sees the cause instead of an unexplained dead end.
 */
public class MailTransportUnavailableException extends RuntimeException {

    /** Machine-readable code so a client can explain the operator remedy. */
    public static final String CODE = "MAIL_TRANSPORT_UNAVAILABLE";

    public MailTransportUnavailableException(String message) {
        super(message);
    }
}
