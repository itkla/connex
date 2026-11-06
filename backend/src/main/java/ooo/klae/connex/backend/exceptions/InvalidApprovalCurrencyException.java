package ooo.klae.connex.backend.exceptions;

/** Blocks document approval evaluation until an administrator corrects a saved policy currency. */
public class InvalidApprovalCurrencyException extends ConflictException {
    public static final String CODE = "APPROVAL_POLICY_CURRENCY_INVALID";

    public InvalidApprovalCurrencyException() {
        super("Correct the currency of the active approval policy before evaluating this document");
    }
}
