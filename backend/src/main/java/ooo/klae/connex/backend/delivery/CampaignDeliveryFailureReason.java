package ooo.klae.connex.backend.delivery;

import java.util.Locale;

/** Bounded reason codes safe to project on campaign recipient surfaces. */
public enum CampaignDeliveryFailureReason {
    PROVIDER_TIMEOUT("provider_timeout"),
    PROVIDER_REJECTED("provider_rejected"),
    DEADLINE_AMBIGUOUS("deadline_ambiguous"),
    DELIVERY_TARGET_CHANGED("delivery_target_changed"),
    RELAY_ERROR("relay_error");

    private final String token;

    CampaignDeliveryFailureReason(String token) {
        this.token = token;
    }

    /** @return stable API token */
    public String token() {
        return token;
    }

    /**
     * Classifies internal provider detail without returning that detail to callers.
     *
     * @param detail internal bounded failure detail
     * @param ambiguous whether provider egress may already have occurred
     * @return a bounded reason code
     */
    public static CampaignDeliveryFailureReason classify(String detail, boolean ambiguous) {
        String normalized = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (ambiguous && normalized.contains("deadline")) {
            return DEADLINE_AMBIGUOUS;
        }
        if (normalized.contains("deadline")
                || normalized.contains("timeout")
                || normalized.contains("timed out")) {
            return PROVIDER_TIMEOUT;
        }
        if (normalized.contains("reject") || normalized.contains("status ")) {
            return PROVIDER_REJECTED;
        }
        return RELAY_ERROR;
    }
}
