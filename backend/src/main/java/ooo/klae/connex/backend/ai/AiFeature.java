package ooo.klae.connex.backend.ai;

/**
 * AI features with their stable audit/cache wire keys and input capabilities.
 */
public enum AiFeature {
    DEAL_BRIEF("deal.brief", false),
    DEAL_RISK_RATIONALE("deal.risk_rationale", false),
    INTRO_RATIONALE("intro.rationale", false),
    REPORT_COMPOSER("report.composer", false),
    REPORT_NARRATIVE("report.narrative", false),
    ASSISTANT_CHAT("assistant.chat", false),
    BUSINESS_CARD_EXTRACTION("business_card.scan", true);

    private final String wireKey;
    private final boolean imageInputRequired;

    AiFeature(String wireKey, boolean imageInputRequired) {
        this.wireKey = wireKey;
        this.imageInputRequired = imageInputRequired;
    }

    /**
     * Returns the stable feature key used in audit and cache records.
     * @return stable wire key
     */
    public String wireKey() {
        return wireKey;
    }

    /**
     * Returns whether this feature requires an image-capable provider.
     * @return true for embedded-image features
     */
    public boolean requiresImageInput() {
        return imageInputRequired;
    }
}
