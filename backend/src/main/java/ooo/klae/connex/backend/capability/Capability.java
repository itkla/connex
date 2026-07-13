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

    /** Instance-managed mail transport. */
    MANAGED_MAIL
}
