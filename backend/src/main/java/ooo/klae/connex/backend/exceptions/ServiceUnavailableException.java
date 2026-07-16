package ooo.klae.connex.backend.exceptions;

/**
 * Raised when this instance must refuse to serve a request it cannot serve
 * safely — e.g. an organization whose placement registry entry demands routing
 * this deployment does not provide. Mapped to HTTP 503 so the client retries
 * against the correct deployment rather than silently reading the wrong data.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
