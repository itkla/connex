package ooo.klae.connex.backend.exceptions;

/** Refuses a manual-value takeover until the caller reviews and confirms the current amount. */
public class DealLineValueConsentException extends ConflictException {
    public static final String REQUIRED = "DEAL_LINE_VALUE_CONSENT_REQUIRED";
    public static final String STALE = "DEAL_LINE_VALUE_CONSENT_STALE";

    private final String code;

    private DealLineValueConsentException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Refuses a takeover whose caller has not confirmed the replacement value. */
    public static DealLineValueConsentException required() {
        return new DealLineValueConsentException(REQUIRED,
            "Confirm replacing the manual deal value with line-item totals");
    }

    /** Refuses confirmation after the reviewed amount, currency, or manual source changes. */
    public static DealLineValueConsentException stale() {
        return new DealLineValueConsentException(STALE,
            "Review the current manual deal amount and currency before confirming again");
    }

    /** Returns the stable code used to select localized review guidance. */
    public String getCode() {
        return code;
    }
}
