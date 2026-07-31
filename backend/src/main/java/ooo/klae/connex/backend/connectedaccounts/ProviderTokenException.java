package ooo.klae.connex.backend.connectedaccounts;

/**
 * A token exchange failed. Carries a coarse machine-readable code only — provider error bodies
 * may echo request material and are never propagated to clients or logs verbatim.
 */
public class ProviderTokenException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public ProviderTokenException(String code, String message) {
        this(code, false, message);
    }

    public ProviderTokenException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public ProviderTokenException(String code, String message, Throwable cause) {
        this(code, false, message, cause);
    }

    public ProviderTokenException(
            String code, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
