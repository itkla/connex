package ooo.klae.connex.backend.ai.masking;

/**
 * Token namespace for CRM identifiers. Prefixes are intentionally short and structured because
 * provider models copy placeholders like {@code {{P1}}} more reliably than long random values.
 */
public enum EntityKind {
    PERSON("P"),
    COMPANY("C"),
    DEAL("D"),
    EMAIL("E"),
    PHONE("H");

    private final String tokenPrefix;

    EntityKind(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    /**
     * Token prefix used in request-local placeholders.
     * @return the single-letter token prefix
     */
    public String tokenPrefix() {
        return tokenPrefix;
    }
}
