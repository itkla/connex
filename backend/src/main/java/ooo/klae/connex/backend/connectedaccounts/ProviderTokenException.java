package ooo.klae.connex.backend.connectedaccounts;

/**
 * A token exchange failed. Carries a coarse machine-readable code only — provider error bodies
 * may echo request material and are never propagated to clients or logs verbatim.
 */
public class ProviderTokenException extends RuntimeException {

    private final String code;

    public ProviderTokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ProviderTokenException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
