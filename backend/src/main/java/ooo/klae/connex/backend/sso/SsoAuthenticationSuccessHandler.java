package ooo.klae.connex.backend.sso;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SsoLoginResult;
import ooo.klae.connex.backend.services.SsoLoginService;

/**
 * Completes an OIDC login once the IdP has authenticated the user. Reads the
 * organization from the {@code org-<id>} registration id and the identity/email from
 * the {@link OidcUser}, then delegates the account resolution to
 * {@link SsoLoginService}. A resolved user is signed in through the shared session
 * ceremony ({@link AuthService#establishAuthenticatedSession}) and sent to the app; a
 * link-required outcome is bounced to the linking screen without a session. Every
 * redirect targets the trusted frontend origin, never a request-supplied URL.
 */
@Component
@RequiredArgsConstructor
public class SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final String REGISTRATION_PREFIX = "org-";

    private final SsoLoginService ssoLoginService;
    private final AuthService authService;
    private final MailProperties mailProperties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        String frontendBase = mailProperties.getAppBaseUrl();
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(token.getPrincipal() instanceof OidcUser user)) {
            response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
            return;
        }
        Integer orgId = parseOrgId(token.getAuthorizedClientRegistrationId());
        if (orgId == null) {
            response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
            return;
        }
        boolean emailVerified = Boolean.TRUE.equals(user.getEmailVerified());
        SsoLoginResult result = ssoLoginService.resolve("oidc", user.getIssuer().toString(), user.getSubject(),
                user.getEmail(), emailVerified, orgId, user.getFullName());
        switch (result) {
            case SsoLoginResult.Login login -> {
                authService.establishAuthenticatedSession(login.user(), request, response);
                response.sendRedirect(frontendBase + "/dashboard");
            }
            case SsoLoginResult.LinkRequired _ -> response.sendRedirect(frontendBase + "/sso/link?e=1");
        }
    }

    private static Integer parseOrgId(String registrationId) {
        if (registrationId == null || !registrationId.startsWith(REGISTRATION_PREFIX)) {
            return null;
        }
        try {
            return Integer.valueOf(registrationId.substring(REGISTRATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
