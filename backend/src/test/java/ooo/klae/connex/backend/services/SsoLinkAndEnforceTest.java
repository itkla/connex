package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.SsoLinkChallenge;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoLinkChallengeMapper;

/**
 * Exercises the SSO account-linking challenge (password-confirm-once) and the enforce-SSO
 * gate against real mappers: a correct password links the identity and consumes a single-use
 * challenge, a wrong password is refused without linking or consuming, an expired challenge is
 * a non-enumerating 404, and an enforced user is barred from password login.
 */
class SsoLinkAndEnforceTest extends AbstractServiceTest {

    private static final String PROVIDER = "oidc";
    private static final String ISSUER = "https://idp.example.com";

    @Autowired private SsoLinkService ssoLinkService;
    @Autowired private SsoConnectionService ssoConnectionService;
    @Autowired private AuthService authService;
    @Autowired private SsoLinkChallengeMapper ssoLinkChallengeMapper;
    @Autowired private FederatedIdentityMapper federatedIdentityMapper;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private int orgId;

    @BeforeEach
    void resolveOrg() {
        orgId = workspaceMapper.getOrgId(workspace.getId());
    }

    @Test
    void confirm_correctPassword_linksIdentityConsumesChallengeAndSignsIn() {
        User user = userWithPassword("Correct1!");
        String token = ssoLinkService.createChallenge(linkRequired(user.getId(), "sub-a"));

        User signedIn = ssoLinkService.confirm(token, "Correct1!", "203.0.113.10",
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertEquals(user.getId(), signedIn.getId());
        FederatedIdentity identity = federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-a");
        assertNotNull(identity, "a correct-password confirm must link the IdP identity");
        assertEquals(user.getId(), identity.getUserId());
        assertEquals(orgId, identity.getOrgId());

        assertThrows(ResourceNotFoundException.class, () ->
                ssoLinkService.confirm(token, "Correct1!", "203.0.113.10",
                        new MockHttpServletRequest(), new MockHttpServletResponse()),
                "a consumed single-use challenge must not be redeemable a second time");
    }

    @Test
    void confirm_wrongPassword_isRefusedWithoutLinkingOrConsuming() {
        User user = userWithPassword("Correct1!");
        String token = ssoLinkService.createChallenge(linkRequired(user.getId(), "sub-b"));

        assertThrows(BadCredentialsException.class, () ->
                ssoLinkService.confirm(token, "Wrong9!Aa", "203.0.113.11",
                        new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-b"),
                "a wrong password must not link any identity");

        User recovered = ssoLinkService.confirm(token, "Correct1!", "203.0.113.12",
                new MockHttpServletRequest(), new MockHttpServletResponse());
        assertEquals(user.getId(), recovered.getId(),
                "a wrong password must leave the challenge redeemable for a later correct attempt");
    }

    @Test
    void confirm_expiredChallenge_isNonEnumeratingNotFound() {
        User user = userWithPassword("Correct1!");
        String rawToken = "expired-raw-token-" + unique();
        SsoLinkChallenge expired = new SsoLinkChallenge();
        expired.setTokenHash(sha256Hex(rawToken));
        expired.setUserId(user.getId());
        expired.setProvider(PROVIDER);
        expired.setIssuer(ISSUER);
        expired.setExternalSubject("sub-c");
        expired.setOrgId(orgId);
        ssoLinkChallengeMapper.insert(expired, -1);

        assertThrows(ResourceNotFoundException.class, () ->
                ssoLinkService.confirm(rawToken, "Correct1!", "203.0.113.13",
                        new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-c"),
                "an expired challenge must link nothing");
    }

    @Test
    void isSsoEnforcedForUser_trueForEnforcedMember_falseOtherwise() {
        User enforced = enrolledInEnforcingOrg();
        assertTrue(ssoConnectionService.isSsoEnforcedForUser(enforced.getId()),
                "an active member of an enforcing organization must be SSO-enforced");

        User plain = newUser();
        assertFalse(ssoConnectionService.isSsoEnforcedForUser(plain.getId()),
                "a member of no enforcing organization must not be SSO-enforced");
    }

    @Test
    void login_enforcedUser_isForbidden() {
        User enforced = enrolledInEnforcingOrg();

        assertThrows(ForbiddenException.class, () ->
                authService.login(new LoginDto(enforced.getUsername(), "irrelevant"),
                        new MockHttpServletRequest(), new MockHttpServletResponse()),
                "password login must be refused for an SSO-enforced account before authentication");
    }

    private SsoLoginResult.LinkRequired linkRequired(int userId, String subject) {
        return new SsoLoginResult.LinkRequired(userId, PROVIDER, ISSUER, subject, orgId);
    }

    private User userWithPassword(String rawPassword) {
        User user = newUser();
        userMapper.updatePasswordHash(user.getId(), passwordEncoder.encode(rawPassword));
        return userMapper.getUserById(user.getId());
    }

    private User enrolledInEnforcingOrg() {
        Organization org = new Organization();
        org.setName("Enforced " + unique());
        org.setSlug("enforced-" + unique());
        organizationMapper.insert(org);

        Workspace ws = new Workspace();
        ws.setName("Enforced WS " + unique());
        ws.setSlug("enforced-ws-" + unique());
        ws.setOrgId(org.getId());
        workspaceMapper.insert(ws);

        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        SsoConnection connection = new SsoConnection();
        connection.setOrgId(org.getId());
        connection.setProtocol(PROVIDER);
        connection.setEnabled(true);
        connection.setEnforceSso(true);
        connection.setJitWorkspaceId(ws.getId());
        connection.setDefaultRole("member");
        connection.setOidcScopes("openid,email,profile");
        ssoConnectionMapper.upsert(connection);
        return member;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
