package ooo.klae.connex.backend.ai.provider;

/** Sanitized provider HTTP rejection used for bounded protocol fallbacks. */
public class AiProviderRequestRejectedException extends AiProviderException {
    /** Upper bound for the retained provider error detail, applied at construction. */
    public static final int MAX_DETAIL_LENGTH = 300;

    private final int statusCode;
    private final String providerDetail;

    public AiProviderRequestRejectedException(String providerLabel, int statusCode) {
        this(providerLabel, statusCode, null);
    }

    public AiProviderRequestRejectedException(
            String providerLabel, int statusCode, String providerDetail) {
        super(providerLabel + " invocation failed with status " + statusCode);
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("AI provider status code is invalid");
        }
        this.statusCode = statusCode;
        this.providerDetail = boundDetail(providerDetail);
    }

    /** @return sanitized HTTP response status code */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Bounded, control-character-free excerpt of the provider's error body, or {@code null}.
     * The invocation audit boundary omits this value for unmasked requests because a provider may
     * echo request content even after transport-level credential redaction.
     */
    public String providerDetail() {
        return providerDetail;
    }

    /** @return whether the provider rejected the request with a client-error status */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode <= 499;
    }

    /** @return whether this rejection may indicate an unsupported structured-output request shape */
    public boolean permitsStructuredOutputFallback() {
        return statusCode == 400 || statusCode == 422;
    }

    private static String boundDetail(String detail) {
        if (detail == null) {
            return null;
        }
        String cleaned = detail.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_DETAIL_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_DETAIL_LENGTH);
    }
}
