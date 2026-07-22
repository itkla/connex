package ooo.klae.connex.backend.dto;

import org.springframework.security.web.csrf.CsrfToken;

/**
 * CSRF bootstrap data plus an opaque authenticated-session generation identity.
 * @param token CSRF token value, or null when CSRF protection is disabled
 * @param headerName header used to submit the token, or null when disabled
 * @param parameterName form parameter used to submit the token, or null when disabled
 * @param requestIdentity opaque current principal/session generation, or null when unauthenticated
 */
public record CsrfBootstrapDto(
        String token,
        String headerName,
        String parameterName,
        String requestIdentity) {

    /**
     * Creates the bootstrap response without exposing principal or servlet-session identifiers.
     * @param token current CSRF token
     * @param requestIdentity opaque authenticated-session generation
     * @return bootstrap response
     */
    public static CsrfBootstrapDto of(CsrfToken token, String requestIdentity) {
        if (token == null) {
            return new CsrfBootstrapDto(null, null, null, requestIdentity);
        }
        return new CsrfBootstrapDto(
                token.getToken(), token.getHeaderName(), token.getParameterName(), requestIdentity);
    }
}
