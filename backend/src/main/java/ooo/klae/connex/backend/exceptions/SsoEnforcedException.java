package ooo.klae.connex.backend.exceptions;

public class SsoEnforcedException extends ForbiddenException {
    public static final String CODE = "SSO_ENFORCED";

    public SsoEnforcedException() {
        super("This account must sign in with SSO");
    }

    public String getCode() {
        return CODE;
    }
}
