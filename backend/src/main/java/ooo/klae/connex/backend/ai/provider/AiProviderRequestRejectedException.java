package ooo.klae.connex.backend.ai.provider;

/** Sanitized provider HTTP rejection used only for bounded structured-output fallback. */
public class AiProviderRequestRejectedException extends AiProviderException {
    private final int statusCode;

    public AiProviderRequestRejectedException(String providerLabel, int statusCode) {
        super(providerLabel + " invocation failed with status " + statusCode);
        this.statusCode = statusCode;
    }

    /** @return whether this rejection may indicate an unsupported structured-output request shape */
    public boolean permitsStructuredOutputFallback() {
        return statusCode == 400 || statusCode == 422;
    }
}
