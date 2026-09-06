package ooo.klae.connex.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.publicapi.PublicApiPaths;

/**
 * Applies the API response-header contract to responses produced before Spring Security runs.
 */
public final class SecurityResponseHeaders {
    static final String CONTENT_SECURITY_POLICY =
        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String REFERRER_POLICY = "strict-origin-when-cross-origin";
    static final String PUBLIC_API_REFERRER_POLICY = "no-referrer";

    private SecurityResponseHeaders() {
    }

    static void apply(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Cache-Control", "no-store");
    }

    /** Applies request-aware pre-security headers, including the public-plane policy and secure HSTS. */
    public static void apply(HttpServletRequest request, HttpServletResponse response) {
        apply(response);
        if (PublicApiPaths.isPublicRequest(request)) {
            response.setHeader("Referrer-Policy", PUBLIC_API_REFERRER_POLICY);
        }
        if (request.isSecure()) {
            response.setHeader(
                "Strict-Transport-Security",
                "max-age=31536000; includeSubDomains");
        }
    }

}
