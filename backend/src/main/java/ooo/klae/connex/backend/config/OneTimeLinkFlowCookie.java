package ooo.klae.connex.backend.config;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.tenant.WorkspaceCookieProperties;

/** Writes and clears purpose-specific HttpOnly credentials for token-free browser link flows. */
@Component
public class OneTimeLinkFlowCookie {

    public static final String PASSWORD_RESET = "connex_password_reset_flow";
    public static final String REGISTRATION_VERIFICATION = "connex_registration_verification_flow";
    public static final String EMAIL_CHANGE = "connex_email_change_flow";
    public static final String WORKSPACE_INVITE = "connex_workspace_invite_flow";
    public static final String WORKSPACE_INVITE_LINK = "connex_workspace_invite_link_flow";
    public static final String SSO_LINK = "connex_sso_link_flow";

    private final WorkspaceCookieProperties properties;

    public OneTimeLinkFlowCookie(WorkspaceCookieProperties properties) {
        this.properties = properties;
    }

    /** Sets a short-lived flow grant on the narrow API path that consumes it. */
    public void set(HttpServletResponse response, Purpose purpose, String value, Duration lifetime) {
        response.addHeader(HttpHeaders.SET_COOKIE, builder(purpose, value)
            .maxAge(lifetime)
            .build()
            .toString());
    }

    /** Expires a flow grant after its one allowed final operation. */
    public void clear(HttpServletResponse response, Purpose purpose) {
        response.addHeader(HttpHeaders.SET_COOKIE, builder(purpose, "")
            .maxAge(Duration.ZERO)
            .build()
            .toString());
    }

    private ResponseCookie.ResponseCookieBuilder builder(Purpose purpose, String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name(purpose), value)
            .path(path(purpose))
            .httpOnly(true)
            .sameSite("Strict");
        if (properties.isEffectiveSecure()) {
            builder.secure(true);
        }
        return builder;
    }

    private static String name(Purpose purpose) {
        return switch (purpose) {
            case PASSWORD_RESET -> PASSWORD_RESET;
            case REGISTRATION_VERIFICATION -> REGISTRATION_VERIFICATION;
            case EMAIL_CHANGE -> EMAIL_CHANGE;
            case WORKSPACE_INVITE -> WORKSPACE_INVITE;
            case WORKSPACE_INVITE_LINK -> WORKSPACE_INVITE_LINK;
            case SSO_LINK -> SSO_LINK;
        };
    }

    private static String path(Purpose purpose) {
        return switch (purpose) {
            case PASSWORD_RESET -> "/api/auth/reset-password";
            case REGISTRATION_VERIFICATION -> "/api/auth/verify-email";
            case EMAIL_CHANGE -> "/api/auth/email-change";
            case WORKSPACE_INVITE -> "/api/invites";
            case WORKSPACE_INVITE_LINK -> "/api/invite-links";
            case SSO_LINK -> "/api/auth/sso/link";
        };
    }
}
