package ooo.klae.connex.backend.sso;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SocialLoginService;
import ooo.klae.connex.backend.services.SsoLinkService;
import ooo.klae.connex.backend.services.SsoLoginResult;
import ooo.klae.connex.backend.services.SsoLoginService;

/**
 * Completes an SSO login once the IdP has authenticated the user, over both OIDC and SAML.
 * Reads the organization from the {@code org-<id>} registration id and the identity/email from
 * the {@link OidcUser} (OIDC) or the {@link Saml2ResponseAssertionAccessor} carried by a
 * {@link Saml2AssertionAuthentication} (SAML), then delegates the account resolution to
 * {@link SsoLoginService}. A resolved user is signed in through the shared session ceremony
 * ({@link AuthService#establishAuthenticatedSession}) and sent to the app; a link-required outcome
 * is bounced to the linking screen without a session. Every redirect targets the trusted frontend
 * origin, never a request-supplied URL.
 */
@Component
@RequiredArgsConstructor
public class SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final String REGISTRATION_PREFIX = "org-";
    private static final List<String> EMAIL_ATTRIBUTES = List.of(
            "email", "urn:oid:0.9.2342.19200300.100.1.3", "mail",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");
    private static final List<String> DISPLAY_NAME_ATTRIBUTES = List.of(
            "displayName", "urn:oid:2.16.840.1.113730.3.1.241");
    private static final List<String> FIRST_NAME_ATTRIBUTES = List.of(
            "firstName", "givenName", "urn:oid:2.5.4.42",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname");
    private static final List<String> LAST_NAME_ATTRIBUTES = List.of(
            "lastName", "surname", "sn", "urn:oid:2.5.4.4",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname");

    private final SsoLoginService ssoLoginService;
    private final SocialLoginService socialLoginService;
    private final SsoLinkService ssoLinkService;
    private final AuthService authService;
    private final MailProperties mailProperties;
    private final SsoConnectionMapper ssoConnectionMapper;
    private final WorkspaceMapper workspaceMapper;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        String frontendBase = mailProperties.getAppBaseUrl();
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser user) {
            handleOidc(token, user, request, response, frontendBase);
            return;
        }
        if (authentication instanceof Saml2AssertionAuthentication saml && saml.getCredentials() != null) {
            handleSaml(saml, request, response, frontendBase);
            return;
        }
        response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
    }

    private void handleOidc(OAuth2AuthenticationToken token, OidcUser user, HttpServletRequest request,
            HttpServletResponse response, String frontendBase) throws IOException {
        String registrationId = token.getAuthorizedClientRegistrationId();
        if (SocialLoginClientRegistrations.GOOGLE.equals(registrationId)
                || SocialLoginClientRegistrations.MICROSOFT.equals(registrationId)) {
            handleSocial(registrationId, user, request, response, frontendBase);
            return;
        }
        Integer orgId = parseOrgId(registrationId);
        if (orgId == null) {
            response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
            return;
        }
        boolean emailVerified = Boolean.TRUE.equals(user.getEmailVerified());
        SsoLoginResult result = ssoLoginService.resolve("oidc", user.getIssuer().toString(), user.getSubject(),
                user.getEmail(), emailVerified, orgId, user.getFullName());
        completeResolution(result, request, response, frontendBase);
    }

    private void handleSocial(String provider, OidcUser user, HttpServletRequest request,
            HttpServletResponse response, String frontendBase) throws IOException {
        String email = socialEmail(user);
        if (email == null) {
            response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
            return;
        }
        boolean emailVerified = SocialLoginClientRegistrations.MICROSOFT.equals(provider)
                ? isMicrosoftEmailVerified(user)
                : Boolean.TRUE.equals(user.getEmailVerified());
        SsoLoginResult result = socialLoginService.resolve(provider, user.getIssuer().toString(),
                user.getSubject(), email, emailVerified, user.getFullName());
        completeResolution(result, request, response, frontendBase);
    }

    /**
     * Whether Microsoft has verified the account owns its email. Microsoft's {@code email} and
     * {@code preferred_username} claims are attacker-mutable — an admin of any tenant can set them
     * to an address they do not own (the nOAuth attack) — so only {@code xms_edov} ("email domain
     * owner verified") proves ownership, and a tenant can assert it only for domains it truly owns.
     * @param user the authenticated Microsoft user
     * @return true when the asserted email is verifiably owned
     */
    private static boolean isMicrosoftEmailVerified(OidcUser user) {
        Object emailDomainOwnerVerified = user.getAttribute("xms_edov");
        return Boolean.TRUE.equals(emailDomainOwnerVerified)
                || "true".equalsIgnoreCase(String.valueOf(emailDomainOwnerVerified));
    }

    private static String socialEmail(OidcUser user) {
        String email = user.getEmail();
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        Object preferredUsername = user.getAttribute("preferred_username");
        if (preferredUsername instanceof String value && value.contains("@")) {
            return value.trim();
        }
        return null;
    }

    private void handleSaml(Saml2AssertionAuthentication saml, HttpServletRequest request,
            HttpServletResponse response, String frontendBase) throws IOException {
        Integer orgId = parseOrgId(saml.getRelyingPartyRegistrationId());
        if (orgId == null) {
            response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
            return;
        }
        Saml2ResponseAssertionAccessor assertion = saml.getCredentials();
        String email = firstNonBlankAttribute(assertion, EMAIL_ATTRIBUTES);
        if (email == null) {
            response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
            return;
        }
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null || connection.getSamlIdpEntityId() == null) {
            response.sendRedirect(frontendBase + "/auth/login?sso_error=1");
            return;
        }
        String displayName = resolveSamlDisplayName(assertion, email);
        SsoLoginResult result = ssoLoginService.resolve("saml", connection.getSamlIdpEntityId(),
                assertion.getNameId(), email, true, orgId, displayName);
        completeResolution(result, request, response, frontendBase);
    }

    private void completeResolution(SsoLoginResult result, HttpServletRequest request,
            HttpServletResponse response, String frontendBase) throws IOException {
        switch (result) {
            case SsoLoginResult.Login login -> {
                User user = authService.establishAuthenticatedSession(login.user(), request, response);
                boolean hasWorkspace = !workspaceMapper.getWorkspacesForUser(user.getId()).isEmpty();
                response.sendRedirect(frontendBase + (hasWorkspace ? "/dashboard" : "/onboarding"));
            }
            case SsoLoginResult.LinkRequired linkRequired -> {
                String linkToken = ssoLinkService.createChallenge(linkRequired);
                response.sendRedirect(frontendBase + "/sso/link?token="
                        + URLEncoder.encode(linkToken, StandardCharsets.UTF_8));
            }
        }
    }

    private static String resolveSamlDisplayName(Saml2ResponseAssertionAccessor assertion, String email) {
        String display = firstNonBlankAttribute(assertion, DISPLAY_NAME_ATTRIBUTES);
        if (display != null) {
            return display;
        }
        String first = firstNonBlankAttribute(assertion, FIRST_NAME_ATTRIBUTES);
        String last = firstNonBlankAttribute(assertion, LAST_NAME_ATTRIBUTES);
        if (first != null && last != null) {
            return first + " " + last;
        }
        if (first != null) {
            return first;
        }
        if (last != null) {
            return last;
        }
        return email;
    }

    private static String firstNonBlankAttribute(Saml2ResponseAssertionAccessor assertion, List<String> names) {
        for (String name : names) {
            List<Object> values = assertion.getAttribute(name);
            if (values == null || values.isEmpty()) {
                continue;
            }
            Object value = values.get(0);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
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
