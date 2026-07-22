package ooo.klae.connex.backend.services;

/**
 * How the absence of a marketing-consent record is treated when classifying an audience.
 *
 * <p>{@link #OPT_OUT} is the product default: Connex does not require a gathered opt-in before
 * contacting a person, so a missing or {@code unknown} {@code contact_channel_consent} row does not
 * block. Only an explicit {@code revoked} row does, and it is reported as {@code consent_revoked}.
 * Respect for an opt-out remains absolute — it is enforced here and, independently, by the
 * suppression list, which alone is sufficient to stop a send.
 *
 * <p>{@link #OPT_IN} is the stricter default-deny reading kept for jurisdictions or workspaces that
 * need it: only a person with an explicit {@code granted} row is contacted, and everyone else is
 * reported as {@code consent_missing}.
 *
 * <p>The active policy is the single constant {@link AudienceEligibilityService#CONSENT_POLICY}, and
 * every consent decision in the product — audience snapshot classification, dispatch-time re-check,
 * and connector export — resolves through {@link AudienceEligibilityService}, so flipping the policy
 * is a one-line change there. A per-workspace policy surface is a later slice.
 */
public enum ConsentPolicy {

    /** Only an explicit granted consent record admits a person; everyone else is consent_missing. */
    OPT_IN("consent_missing"),

    /** Everyone is admitted unless an explicit revoked consent record exists; they are consent_revoked. */
    OPT_OUT("consent_revoked");

    private final String exclusionReason;

    ConsentPolicy(String exclusionReason) {
        this.exclusionReason = exclusionReason;
    }

    /**
     * The reason token recorded when this policy blocks a person on consent grounds.
     * @return the exclusion/skip reason token
     */
    public String exclusionReason() {
        return exclusionReason;
    }
}
