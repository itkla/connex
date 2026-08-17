package ooo.klae.connex.backend.beans;

/**
 * The closed vocabulary for how a contact originally entered Connex (#559). Record-level
 * provenance complements the per-identity acquisition sources kept by
 * {@code IdentityIntakeService}: identities record which intake path supplied each identifier,
 * while this records the business origin of the relationship itself. A {@code null} source means
 * provenance was never captured — the state of every contact that predates this column — and is
 * never backfilled with a guess.
 */
public enum PersonLeadSource {
    /** Referred by an existing contact or customer. */
    REFERRAL,
    /** Met at an event, conference, or seminar. */
    EVENT,
    /** Arrived through the website or another inbound web channel. */
    WEB,
    /** Sourced by outbound prospecting. */
    OUTBOUND,
    /** Captured from a scanned business card. */
    BUSINESS_CARD,
    /** Created by a CSV import. */
    IMPORT,
    /** Introduced through a partner or channel relationship. */
    PARTNER,
    /** Anything else, described in the source detail. */
    OTHER;

    /** Whether this source may carry a referring contact. */
    public boolean supportsReferrer() {
        return this == REFERRAL || this == PARTNER;
    }
}
