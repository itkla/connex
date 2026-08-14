package ooo.klae.connex.backend.config;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies the API response-header contract to responses produced before Spring Security runs.
 */
final class SecurityResponseHeaders {
    static final String CONTENT_SECURITY_POLICY =
        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String REFERRER_POLICY = "strict-origin-when-cross-origin";

    private SecurityResponseHeaders() {
    }

    static void apply(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }
}
