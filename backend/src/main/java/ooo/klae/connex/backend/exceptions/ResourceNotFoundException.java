package ooo.klae.connex.backend.exceptions;

/** Signals that a requested API resource is unavailable to the caller. */
public class ResourceNotFoundException extends RuntimeException {
    /** Stable API error code for resource lookup failures. */
    public static final String CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(message);
    }

    /** Returns the stable API error code for this failure. */
    public String getCode() {
        return CODE;
    }
}
