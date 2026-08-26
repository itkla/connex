package ooo.klae.connex.backend.password;

/**
 * Credential-write contexts with fixed breached-password availability policy.
 */
public enum PasswordScreeningFlow {
    SELF_REGISTRATION("self_registration", "password"),
    ADMIN_ACCOUNT_CREATION("admin_account_creation", "password"),
    BOOTSTRAP_OWNER("bootstrap_owner", "password"),
    SELF_SERVICE_RESET("self_service_reset", "newPassword");

    private final String auditValue;
    private final String field;

    PasswordScreeningFlow(String auditValue, String field) {
        this.auditValue = auditValue;
        this.field = field;
    }

    public String auditValue() {
        return auditValue;
    }

    public String field() {
        return field;
    }
}
