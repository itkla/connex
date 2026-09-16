package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;

/**
 * Exercises verified OIDC callback completion with real account, identity, membership, and session
 * services. Provider token validation is represented by an authenticated OIDC principal; subsequent
 * HTTP requests traverse the application's security filter chain without a surrounding transaction.
 */
@SpringBootTest(properties = "connex.signup.mode=invite")
class SsoAdmissionIntegrationTest {

    private static final String ISSUER = "https://attacker.example.test";
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private SsoAuthenticationSuccessHandler successHandler;
    @Autowired private SsoConnectionService ssoConnectionService;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private SsoDomainMapper ssoDomainMapper;
    @Autowired private FederatedIdentityMapper identityMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;

    private Workspace attackerWorkspace;
    private Workspace victimWorkspace;
    private String domain;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clearContext();
        attackerWorkspace = newWorkspace();
        victimWorkspace = newWorkspace();
        domain = "sso-" + UUID.randomUUID() + ".example.test";
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void nullOrgSocialIdentityCountsAsForeign() {
        User victim = newUser();
        link(victim, null, "google", GOOGLE_ISSUER, "google-" + UUID.randomUUID());

        assertEquals(1, identityMapper.countByUserIdExcludingOrg(
                victim.getId(), attackerWorkspace.getOrgId()));
    }

    @Test
    void enterpriseCallbackCannotAdoptAnotherTenantsSocialAccount() throws Exception {
        seedAttackerConnection();
        User victim = newUser();
        workspaceMapper.addMember(victimWorkspace.getId(), victim.getId(), "member");
        FederatedIdentity original = link(victim, null, "google", GOOGLE_ISSUER,
                "google-" + UUID.randomUUID());
        String subject = "attacker-" + UUID.randomUUID();

        CallbackResult callback = completeCallback("org-" + attackerWorkspace.getOrgId(),
                ISSUER, subject, victim.getEmail());

        assertRefused(callback);
        assertNull(identityMapper.findByOrgProviderIssuerSubject(
                attackerWorkspace.getOrgId(), "oidc", ISSUER, subject));
        assertEquals(original, identityMapper.findByProviderIssuerSubject(
                "google", GOOGLE_ISSUER, original.getExternalSubject()));
        assertNull(workspaceMapper.getMember(attackerWorkspace.getId(), victim.getId()));
        assertTrue(workspaceMapper.isMember(victimWorkspace.getId(), victim.getId()));
        mockMvc.perform(get("/api/persons/page").session(callback.session())
                .cookie(new jakarta.servlet.http.Cookie("connex_workspace", String.valueOf(victimWorkspace.getId()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enterpriseCallbackCannotAdoptExistingPasswordlessAccountWithoutIdentity() throws Exception {
        seedAttackerConnection();
        User victim = newUser();
        workspaceMapper.addMember(victimWorkspace.getId(), victim.getId(), "member");
        String subject = "attacker-" + UUID.randomUUID();

        CallbackResult callback = completeCallback("org-" + attackerWorkspace.getOrgId(),
                ISSUER, subject, victim.getEmail());

        assertRefused(callback);
        assertNull(identityMapper.findByOrgProviderIssuerSubject(
                attackerWorkspace.getOrgId(), "oidc", ISSUER, subject));
        assertNull(workspaceMapper.getMember(attackerWorkspace.getId(), victim.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void inviteModeRefusesNewSocialAccountWithoutIdentityOrSession(String provider) throws Exception {
        String email = UUID.randomUUID() + "@" + domain;
        String issuer = "google".equals(provider) ? GOOGLE_ISSUER : "https://login.microsoftonline.com/test/v2.0";
        String subject = "new-" + UUID.randomUUID();

        CallbackResult callback = completeCallback(provider, issuer, subject, email);

        assertRefused(callback);
        assertNull(userMapper.getUserByEmail(email));
        assertNull(identityMapper.findByProviderIssuerSubject(provider, issuer, subject));
    }

    @Test
    void inviteModeStillSignsInLinkedGoogleUser() throws Exception {
        User existing = newUser();
        workspaceMapper.addMember(victimWorkspace.getId(), existing.getId(), "member");
        String subject = "returning-" + UUID.randomUUID();
        link(existing, null, "google", GOOGLE_ISSUER, subject);

        CallbackResult callback = completeCallback("google", GOOGLE_ISSUER, subject, existing.getEmail());

        assertTrue(callback.redirect().endsWith("/dashboard"));
        SecurityContext saved = assertInstanceOf(SecurityContext.class, callback.session().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        assertNotNull(saved.getAuthentication());
        User principal = assertInstanceOf(User.class, saved.getAuthentication().getPrincipal());
        assertEquals(existing.getId(), principal.getId());
        FederatedIdentity linked = identityMapper.findByProviderIssuerSubject("google", GOOGLE_ISSUER, subject);
        assertNotNull(linked);
        assertNotNull(linked.getLastLoginAt());
        clearContext();
        mockMvc.perform(get("/api/auth/me").session(callback.session())).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "unaffiliated", "both", "org-role"})
    void domainClaimRefusesVerifiedAccountsOutsideOrgAndRollsBack(String membership) {
        User victim = newUser();
        if ("foreign".equals(membership) || "both".equals(membership)) {
            workspaceMapper.addMember(victimWorkspace.getId(), victim.getId(), "member");
        }
        if ("both".equals(membership) || "org-role".equals(membership)) {
            workspaceMapper.addMember(attackerWorkspace.getId(), victim.getId(), "member");
        }
        if ("org-role".equals(membership)) {
            orgMemberMapper.addMember(victimWorkspace.getOrgId(), victim.getId(), "admin");
        }
        User actor = authenticateConfigurationOwner();
        SsoConnectionRequest request = domainRequest();

        assertThrows(BadRequestException.class, () -> ssoConnectionService.save(
                attackerWorkspace.getId(), actor.getId(), request));

        assertNull(ssoDomainMapper.findOrgByDomain(domain));
        assertNull(ssoConnectionMapper.findByOrg(attackerWorkspace.getOrgId()));
    }

    @Test
    void domainClaimAllowsVerifiedAccountsOnlyInThisOrg() {
        User member = newUser();
        workspaceMapper.addMember(attackerWorkspace.getId(), member.getId(), "member");
        User actor = authenticateConfigurationOwner();

        ssoConnectionService.save(attackerWorkspace.getId(), actor.getId(), domainRequest());

        assertEquals(attackerWorkspace.getOrgId(), ssoDomainMapper.findOrgByDomain(domain));
    }

    @Test
    void domainClaimIgnoresUnacceptedForeignInvitation() {
        User member = newUser();
        workspaceMapper.addMember(attackerWorkspace.getId(), member.getId(), "member");
        workspaceMapper.addPendingMember(victimWorkspace.getId(), member.getId(), "member");
        User actor = authenticateConfigurationOwner();

        ssoConnectionService.save(attackerWorkspace.getId(), actor.getId(), domainRequest());

        assertEquals(attackerWorkspace.getOrgId(), ssoDomainMapper.findOrgByDomain(domain));
        assertTrue(workspaceMapper.isMemberIncludingPending(victimWorkspace.getId(), member.getId()));
        assertFalse(workspaceMapper.isMember(victimWorkspace.getId(), member.getId()));
    }

    @Test
    void domainClaimAllowsVerifiedOrgOnlyAdministrator() {
        User administrator = newUser();
        orgMemberMapper.addMember(attackerWorkspace.getOrgId(), administrator.getId(), "admin");
        User actor = authenticateConfigurationOwner();

        ssoConnectionService.save(attackerWorkspace.getId(), actor.getId(), domainRequest());

        assertEquals(attackerWorkspace.getOrgId(), ssoDomainMapper.findOrgByDomain(domain));
        assertFalse(workspaceMapper.isMemberIncludingPending(attackerWorkspace.getId(), administrator.getId()));
    }

    private SsoConnectionRequest domainRequest() {
        SsoConnectionRequest request = new SsoConnectionRequest();
        request.setProtocol("oidc");
        request.setEnabled(false);
        request.setJitWorkspaceId(attackerWorkspace.getId());
        request.setDomains(List.of("@" + domain.toUpperCase(java.util.Locale.ROOT)));
        return request;
    }

    private User authenticateConfigurationOwner() {
        User actor = newUser();
        workspaceMapper.addMember(attackerWorkspace.getId(), actor.getId(), "member");
        orgMemberMapper.addMember(attackerWorkspace.getOrgId(), actor.getId(), "owner");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, actor.getId());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, actor.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return actor;
    }

    private CallbackResult completeCallback(String registration, String issuer, String subject, String email)
            throws Exception {
        OidcIdToken idToken = new OidcIdToken("verified-test-token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("iss", issuer, "sub", subject, "email", email, "email_verified", true,
                        "xms_edov", true, "name", "OIDC test user"));
        DefaultOidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), registration);
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContext upstream = SecurityContextHolder.createEmptyContext();
        upstream.setAuthentication(token);
        SecurityContextHolder.setContext(upstream);
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, upstream);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        successHandler.onAuthenticationSuccess(request, response, token);

        MockHttpSession session = assertInstanceOf(MockHttpSession.class, request.getSession(false));
        String redirect = response.getRedirectedUrl();
        assertNotNull(redirect);
        return new CallbackResult(session, redirect);
    }

    private void assertRefused(CallbackResult callback) throws Exception {
        assertTrue(callback.redirect().endsWith("/auth/login?sso_error=1"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(callback.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        assertNull(callback.session().getAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR));
        clearContext();
        mockMvc.perform(get("/api/auth/me").session(callback.session())).andExpect(status().isUnauthorized());
    }

    private void seedAttackerConnection() {
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(attackerWorkspace.getOrgId());
        connection.setProtocol("oidc");
        connection.setEnabled(true);
        connection.setJitWorkspaceId(attackerWorkspace.getId());
        connection.setDefaultRole("member");
        connection.setOidcIssuer(ISSUER);
        connection.setOidcClientId("attacker-client");
        connection.setOidcScopes("openid,email,profile");
        ssoConnectionMapper.upsert(connection);
        ssoDomainMapper.insert(domain, attackerWorkspace.getOrgId());
    }

    private FederatedIdentity link(User user, Integer orgId, String provider, String issuer, String subject) {
        FederatedIdentity identity = new FederatedIdentity();
        identity.setUserId(user.getId());
        identity.setOrgId(orgId);
        identity.setProvider(provider);
        identity.setIssuer(issuer);
        identity.setExternalSubject(subject);
        identityMapper.insert(identity);
        FederatedIdentity stored = identityMapper.findByProviderIssuerSubject(provider, issuer, subject);
        assertNotNull(stored);
        return stored;
    }

    private User newUser() {
        User user = new User();
        String suffix = UUID.randomUUID().toString();
        user.setUsername("sso-" + suffix);
        user.setDisplayName("SSO test user");
        user.setEmail(suffix + "@" + domain);
        user.setEmailVerified(true);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private Workspace newWorkspace() {
        String slug = "sso-" + UUID.randomUUID();
        Organization organization = new Organization();
        organization.setName(slug);
        organization.setSlug(slug);
        organizationMapper.insert(organization);
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private record CallbackResult(MockHttpSession session, String redirect) {}
}
