package ooo.klae.connex.backend.ai;

/**
 * AI features with their stable audit/cache wire keys and input capabilities.
 */
public enum AiFeature {
    DEAL_BRIEF("deal.brief", ImageInput.NONE),
    DEAL_RISK_RATIONALE("deal.risk_rationale", ImageInput.NONE),
    INTRO_RATIONALE("intro.rationale", ImageInput.NONE),
    REPORT_COMPOSER("report.composer", ImageInput.NONE),
    REPORT_NARRATIVE("report.narrative", ImageInput.NONE),
    ASSISTANT_CHAT("assistant.chat", ImageInput.OPTIONAL),
    BUSINESS_CARD_EXTRACTION("business_card.scan", ImageInput.REQUIRED);

    private final String wireKey;
    private final ImageInput imageInput;

    AiFeature(String wireKey, ImageInput imageInput) {
        this.wireKey = wireKey;
        this.imageInput = imageInput;
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
        return imageInput == ImageInput.REQUIRED;
    }

    /**
     * Returns whether this feature accepts an embedded image when one is available.
     * @return true for optional and required embedded-image features
     */
    public boolean acceptsImageInput() {
        return imageInput != ImageInput.NONE;
    }

    private enum ImageInput {
        NONE,
        OPTIONAL,
        REQUIRED
    }
}
