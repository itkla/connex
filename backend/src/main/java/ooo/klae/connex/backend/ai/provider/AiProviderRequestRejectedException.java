package ooo.klae.connex.backend.ai.provider;

/** Sanitized provider HTTP rejection used for bounded protocol fallbacks. */
public class AiProviderRequestRejectedException extends AiProviderException {
    private final int statusCode;

    public AiProviderRequestRejectedException(String providerLabel, int statusCode) {
        super(providerLabel + " invocation failed with status " + statusCode);
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("AI provider status code is invalid");
        }
        this.statusCode = statusCode;
    }

    /** @return sanitized HTTP response status code */
    public int statusCode() {
        return statusCode;
    }

    /** @return whether the provider rejected the request with a client-error status */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode <= 499;
    }

    /** @return whether this rejection may indicate an unsupported structured-output request shape */
    public boolean permitsStructuredOutputFallback() {
        return statusCode == 400 || statusCode == 422;
    }
}
