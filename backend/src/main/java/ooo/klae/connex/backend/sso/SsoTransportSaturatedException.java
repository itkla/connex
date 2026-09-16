package ooo.klae.connex.backend.sso;

import java.io.IOException;

/**
 * Signals that the bounded OIDC transport had no free capacity for a destination within the
 * request deadline. Distinct from a destination refused by the outbound address policy, so
 * operators can tell a capacity problem from a blocked egress target.
 */
public class SsoTransportSaturatedException extends IOException {

    /**
     * Creates the exception.
     * @param message the operator-facing reason, without any destination address
     */
    public SsoTransportSaturatedException(String message) {
        super(message);
    }
}
