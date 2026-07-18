package ooo.klae.connex.backend.capability;

/**
 * Instance-level product capabilities exposed to unauthenticated clients.
 */
public enum Capability {
    /** Organization single sign-on. */
    SSO,

    /** Google social login. */
    SOCIAL_LOGIN_GOOGLE,

    /** Microsoft social login. */
    SOCIAL_LOGIN_MICROSOFT,

    /** Per-user Google connected accounts (mail/calendar OAuth). */
    CONNECTED_ACCOUNTS_GOOGLE,

    /** Per-user Microsoft connected accounts (mail/calendar OAuth). */
    CONNECTED_ACCOUNTS_MICROSOFT,

    /** Instance-managed mail transport. */
    MANAGED_MAIL,

    /** Local business-card OCR with durable private card retention. */
    BUSINESS_CARD_SCANNING,

    /** Reviewed business-card import with durable private card retention. */
    BUSINESS_CARD_IMPORT,

    /** Native email campaign delivery on an extensible channel/provider SPI. */
    CAMPAIGN_DELIVERY
}
