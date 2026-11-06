package ooo.klae.connex.backend.exceptions;

/** Refuses campaign content whose personalization cannot be rendered by the dispatcher. */
public final class UnsupportedCampaignTokenException extends BadRequestException {
    public UnsupportedCampaignTokenException() {
        super("Campaign content supports only the exact {{unsubscribe_url}} token");
    }

    @Override
    public String getCode() {
        return "CAMPAIGN_UNSUPPORTED_TOKEN";
    }
}
