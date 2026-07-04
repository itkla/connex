package ooo.klae.connex.backend.dto;

import lombok.Data;

/**
 * Pre-login answer to "does this email's domain sign in through an IdP?". Returned
 * from an unauthenticated lookup so the login screen can offer an "SSO" affordance and,
 * when {@code enforced}, hide password entry. It carries only what the browser needs to
 * start the flow — the {@code registrationId} to navigate to and the {@code protocol} —
 * and never reveals whether a particular user account exists.
 */
@Data
public class SsoDiscoveryDto {
    private boolean available;
    private String registrationId;
    private String protocol;
    private boolean enforced;

    /**
     * The domain is not routed to an enabled SSO connection; the browser should fall
     * back to password/passkey login.
     * @return an unavailable result
     */
    public static SsoDiscoveryDto unavailable() {
        return new SsoDiscoveryDto();
    }

    /**
     * The domain routes to an enabled SSO connection the browser can start.
     * @param registrationId the {@code org-<id>} registration to navigate to
     * @param protocol the connection protocol ({@code oidc} or {@code saml})
     * @param enforced whether password login is disabled for this organization
     * @return an available result
     */
    public static SsoDiscoveryDto available(String registrationId, String protocol, boolean enforced) {
        SsoDiscoveryDto dto = new SsoDiscoveryDto();
        dto.available = true;
        dto.registrationId = registrationId;
        dto.protocol = protocol;
        dto.enforced = enforced;
        return dto;
    }
}
