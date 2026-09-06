package ooo.klae.connex.backend.publicapi;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Secret-free credential metadata attached to a public API authentication.
 * The authentication principal remains the creating {@code User}, preserving the existing RBAC
 * contract; this value carries both the issued scope set and its live RBAC intersection so policy
 * code can distinguish stored intent from current authority. Public identity exposes only the live
 * set.
 */
public record ApiCredentialPrincipal(
        long credentialId,
        int userId,
        int workspaceId,
        int organizationId,
        String name,
        Set<ApiScope> credentialScopes,
        Set<ApiScope> authorizedScopes,
        LocalDateTime expiresAt) {

    /** Makes both scope sets immutable at the authentication boundary. */
    public ApiCredentialPrincipal {
        credentialScopes = Set.copyOf(credentialScopes);
        authorizedScopes = Set.copyOf(authorizedScopes);
    }
}
