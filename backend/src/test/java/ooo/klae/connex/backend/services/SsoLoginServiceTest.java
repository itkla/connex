package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.OrgAllowedDomainMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.mail.MailProperties;

/**
 * Exercises the SSO federation core against real mappers, with emphasis on the tenant-isolation
 * invariants: identity matching is org-scoped, every email-based resolution requires a verified
 * email whose domain the organization owns in its {@code sso_domain} list, an existing password
 * account demands explicit linking, and an account federated to another organization is never
 * claimed. New allowed-domain emails are provisioned and JIT-joined; everything else is refused
 * with nothing written.
 */
class SsoLoginServiceTest extends AbstractServiceTest {

    private static final String PROVIDER = "oidc";
    private static final String ISSUER = "https://idp.example.com";
    private static final String OWNED_DOMAIN = "example.com";

    @Autowired private SsoLoginService ssoLoginService;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private FederatedIdentityMapper federatedIdentityMapper;
    @Autowired private SsoDomainMapper ssoDomainMapper;
    @Autowired private OrgAllowedDomainMapper orgAllowedDomainMapper;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private AuditService auditService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private SsoAuthenticationSuccessHandler successHandler;
    @Autowired private MailProperties mailProperties;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private TenantLifecycleControlMapper lifecycleMapper;

    private int orgId;

    @BeforeEach
    void seedSsoConnection() {
        orgId = workspaceMapper.getOrgId(workspace.getId());
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(orgId);
        connection.setProtocol("oidc");
        connection.setEnabled(true);
        connection.setEnforceSso(false);
        connection.setJitWorkspaceId(workspace.getId());
        connection.setDefaultRole("member");
        connection.setOidcIssuer(ISSUER);
        connection.setOidcClientId("client-abc");
        connection.setOidcScopes("openid,email,profile");
        ssoConnectionMapper.upsert(connection);
        ssoDomainMapper.insert(OWNED_DOMAIN, orgId);
    }

    private User provisionlessUser(String email) {
        User user = new User();
        user.setUsername("sso-" + email.replaceAll("[^a-z0-9]", ""));
        user.setDisplayName(email);
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setTimezone("UTC");
        user.setPasswordHash(null);
        userMapper.insert(user);
        return user;
    }

    @Test
    void resolve_isBlockedByTheOrgAllowedDomainCeiling() {
        orgAllowedDomainMapper.add(orgId, "acme.com");

        assertThrows(ForbiddenException.class,
                () -> ssoLoginService.resolve(PROVIDER, ISSUER, "sub-ceiling",
                        "newcomer@" + OWNED_DOMAIN, true, orgId, "Newcomer", "client-abc"),
                "SSO must not provision a member the org's own domain ceiling forbids, "
                        + "even though the domain is in the sso_domain routing list");
        assertNull(userMapper.getUserByEmail("newcomer@" + OWNED_DOMAIN),
                "a refused SSO login must write nothing");
    }

    @Test
    void resolve_provisionsWhenTheOrgCeilingAllowsTheDomain() {
        orgAllowedDomainMapper.add(orgId, OWNED_DOMAIN);

        SsoLoginResult result = ssoLoginService.resolve(PROVIDER, ISSUER, "sub-allowed",
                "welcome@" + OWNED_DOMAIN, true, orgId, "Welcome", "client-abc");

        assertInstanceOf(SsoLoginResult.Login.class, result);
        assertTrue(workspaceMapper.isMember(workspace.getId(),
                userMapper.getUserByEmail("welcome@" + OWNED_DOMAIN).getId()));
    }

    @Test
    void resolveFailsClosedWhenTeardownHasClearedTheJitWorkspace() {
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        connection.setJitWorkspaceId(null);
        ssoConnectionMapper.upsert(connection);

        assertThrows(
            ForbiddenException.class,
            () -> ssoLoginService.resolve(
                PROVIDER,
                ISSUER,
                "sub-no-jit-workspace",
                "no-jit-workspace@" + OWNED_DOMAIN,
                true,
                orgId,
                "No JIT Workspace", "client-abc"));
        assertNull(userMapper.getUserByEmail("no-jit-workspace@" + OWNED_DOMAIN));
    }

    @Test
    void resolveFailsClosedWhileTheOrganizationIsTearingDown() {
        jdbcTemplate.update(
            "UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?",
            orgId);

        assertThrows(
            ForbiddenException.class,
            () -> ssoLoginService.resolve(
                PROVIDER,
                ISSUER,
                "sub-tearing-down",
                "tearing-down@" + OWNED_DOMAIN,
                true,
                orgId,
                "Tearing Down", "client-abc"));
        assertNull(userMapper.getUserByEmail("tearing-down@" + OWNED_DOMAIN));
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(
            PROVIDER,
            ISSUER,
            "sub-tearing-down"),
            "a fenced organization must never gain a new federated identity row");
    }

    @Test
    void existingIdentityIsNotTouchedWhileTheOrganizationIsTearingDown() {
        User linked = newUser();
        FederatedIdentity seed = new FederatedIdentity();
        seed.setUserId(linked.getId());
        seed.setOrgId(orgId);
        seed.setProvider(PROVIDER);
        seed.setIssuer(ISSUER);
        seed.setExternalSubject("sub-existing-fenced");
        federatedIdentityMapper.insert(seed);
        jdbcTemplate.update(
            "UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?",
            orgId);

        assertThrows(
            ForbiddenException.class,
            () -> ssoLoginService.resolve(
                PROVIDER,
                ISSUER,
                "sub-existing-fenced",
                linked.getEmail(),
                true,
                orgId,
                "Existing Fenced", "client-abc"));

        assertNull(jdbcTemplate.queryForObject(
            "SELECT last_login_at FROM federated_identity WHERE id = ?",
            java.time.LocalDateTime.class,
            seed.getId()));
    }

    @Test
    void existingIdentity_signsInAndTouchesLastLogin() {
        User linked = newUser();
        FederatedIdentity seed = new FederatedIdentity();
        seed.setUserId(linked.getId());
        seed.setOrgId(orgId);
        seed.setProvider(PROVIDER);
        seed.setIssuer(ISSUER);
        seed.setExternalSubject("sub-existing");
        federatedIdentityMapper.insert(seed);

        SsoLoginResult result = ssoLoginService.resolve(PROVIDER, ISSUER, "sub-existing",
                linked.getEmail(), true, orgId, "Linked User", "client-abc");

        SsoLoginResult.Login login = assertInstanceOf(SsoLoginResult.Login.class, result);
        assertEquals(linked.getId(), login.user().getId());
        assertNotNull(
                federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-existing").getLastLoginAt(),
                "the identity's last_login_at must be stamped on a returning login");
    }

    @Test
    void identityFromAnotherOrg_isNotMatched() {
        User otherOwner = provisionlessUser("owner-" + unique() + "@other.example");
        WorkspaceMembershipDto other = workspaceService.createWorkspace("Other Org", otherOwner.getId());
        int otherOrgId = workspaceMapper.getOrgId(other.getId());
        User foreign = newUser();
        FederatedIdentity seed = new FederatedIdentity();
        seed.setUserId(foreign.getId());
        seed.setOrgId(otherOrgId);
        seed.setProvider(PROVIDER);
        seed.setIssuer(ISSUER);
        seed.setExternalSubject("sub-foreign");
        federatedIdentityMapper.insert(seed);

        assertThrows(ForbiddenException.class, () -> ssoLoginService.resolve(PROVIDER, ISSUER, "sub-foreign",
                "outsider@notowned.example.org", true, orgId, "Outsider", "client-abc"),
                "an identity minted for another org must not sign in through this org, and the "
                        + "foreign email domain is refused");
    }

    @Test
    void verifiedEmailMatchingPasswordAccount_requiresLinkAndWritesNothing() {
        User password = newUser();
        assertNotNull(password.getPassword(), "the fixture account must have a password");

        SsoLoginResult result = ssoLoginService.resolve(PROVIDER, ISSUER, "sub-collision",
                password.getEmail(), true, orgId, "Password User", "client-abc");

        SsoLoginResult.LinkRequired link = assertInstanceOf(SsoLoginResult.LinkRequired.class, result);
        assertEquals(password.getId(), link.existingUserId());
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-collision"),
                "no identity may be minted for a link-required outcome (no auto-link, no session)");
    }

    @Test
    void newVerifiedEmailInOwnedDomain_provisionsMemberAndIdentity() {
        String email = "newcomer@" + OWNED_DOMAIN;

        SsoLoginResult result = ssoLoginService.resolve(PROVIDER, ISSUER, "sub-newcomer",
                email, true, orgId, "New Comer", "client-abc");

        SsoLoginResult.Login login = assertInstanceOf(SsoLoginResult.Login.class, result);
        User created = userMapper.getUserByEmail(email);
        assertNotNull(created, "a new SSO account must be provisioned");
        assertEquals(created.getId(), login.user().getId());
        assertNull(created.getPassword(), "an SSO-provisioned account carries no password");
        assertTrue(workspaceMapper.isMember(workspace.getId(), created.getId()),
                "the new user must be an active member of the JIT workspace");

        FederatedIdentity identity = federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-newcomer");
        assertNotNull(identity, "a federated identity link must be recorded");
        assertEquals(created.getId(), identity.getUserId());
        assertEquals(orgId, identity.getOrgId());
        assertTrue(auditService.recentForOrg(orgId, 50, 0).stream()
                .anyMatch(entry -> "org.sso_user.provision".equals(entry.getAction())
                        && "organization".equals(entry.getEntityType())
                        && Integer.valueOf(orgId).equals(entry.getOrgId())),
                "SSO account provisioning must be visible in the org audit trail");
        assertTrue(auditService.recentForOrg(orgId, 50, 0).stream()
                .anyMatch(entry -> "org.federated_identity.link".equals(entry.getAction())
                        && "organization".equals(entry.getEntityType())
                        && Integer.valueOf(orgId).equals(entry.getOrgId())),
                "SSO federated identity binding must be visible in the org audit trail");
        assertTrue(auditService.recentForOrg(orgId, 50, 0).stream()
                .anyMatch(entry -> "org.workspace_member.sso_provision".equals(entry.getAction())
                        && "organization".equals(entry.getEntityType())
                        && Integer.valueOf(orgId).equals(entry.getOrgId())),
                "SSO JIT workspace membership must be visible in the org audit trail");
    }

    @Test
    void emailDomainNotOwnedByOrg_isRefusedWithNothingWritten() {
        String email = "intruder@blocked.example.com";

        assertThrows(ForbiddenException.class, () ->
                ssoLoginService.resolve(PROVIDER, ISSUER, "sub-intruder", email, true, orgId, "Intruder", "client-abc"));

        assertNull(userMapper.getUserByEmail(email),
                "a domain the org does not own must not provision an account");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-intruder"),
                "a domain the org does not own must not mint an identity");
    }

    @Test
    void unverifiedEmail_isRefusedWithNothingWritten() {
        String email = "unverified@" + OWNED_DOMAIN;

        assertThrows(ForbiddenException.class, () ->
                ssoLoginService.resolve(PROVIDER, ISSUER, "sub-unverified", email, false, orgId, "Unverified", "client-abc"));

        assertNull(userMapper.getUserByEmail(email),
                "an unverified IdP email must never provision an account");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-unverified"),
                "an unverified IdP email must never mint an identity");
    }

    @Test
    void passwordlessAccountFederatedToAnotherOrg_isRefused() {
        User otherOwner = provisionlessUser("owner-" + unique() + "@other.example");
        WorkspaceMembershipDto other = workspaceService.createWorkspace("Other Org", otherOwner.getId());
        int otherOrgId = workspaceMapper.getOrgId(other.getId());
        User victim = provisionlessUser("victim-xorg@" + OWNED_DOMAIN);
        FederatedIdentity foreignLink = new FederatedIdentity();
        foreignLink.setUserId(victim.getId());
        foreignLink.setOrgId(otherOrgId);
        foreignLink.setProvider(PROVIDER);
        foreignLink.setIssuer(ISSUER);
        foreignLink.setExternalSubject("sub-victim-home");
        federatedIdentityMapper.insert(foreignLink);

        assertThrows(ForbiddenException.class, () -> ssoLoginService.resolve(PROVIDER, ISSUER, "sub-victim-claim",
                victim.getEmail(), true, orgId, "Victim", "client-abc"),
                "a passwordless account already federated to another organization must not be claimed");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-victim-claim"),
                "no identity may be minted when refusing a cross-org claim");
    }

    @ParameterizedTest
    @CsvSource({"disabled,false", "disabled,true", "issuer,false", "issuer,true",
            "client,false", "client,true", "protocol,false", "protocol,true",
            "unchanged,false", "unchanged,true"})
    void completionRevalidatesConnectionBeforeSessionOrProvisioning(String change, boolean returning) throws Exception {
        CompletionFixture fixture = completionFixture(returning);
        OAuth2AuthenticationToken authentication = completionAuthentication(fixture);
        changeConnection(fixture.orgId(), change);

        completeAndAssert(fixture, authentication, "unchanged".equals(change));
    }

    @ParameterizedTest
    @CsvSource({"disabled,false", "disabled,true", "issuer,false", "issuer,true"})
    void completionReadsCommittedConnectionAfterWaitingOnOrganizationLock(String change, boolean returning)
            throws Exception {
        CompletionFixture fixture = completionFixture(returning);
        OAuth2AuthenticationToken authentication = completionAuthentication(fixture);
        CountDownLatch updateLocked = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        CountDownLatch callbackAttemptedLock = new CountDownLatch(1);
        TenantLifecycleControlMapper realLifecycle = sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        doAnswer(invocation -> {
            callbackAttemptedLock.countDown();
            return realLifecycle.lockActiveOrganizationForShare(fixture.orgId());
        }).when(lifecycleMapper).lockActiveOrganizationForShare(fixture.orgId());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                assertNotNull(realLifecycle.lockOrganization(fixture.orgId()));
                assertNotNull(ssoConnectionMapper.findByOrgForUpdate(fixture.orgId()));
                changeConnection(fixture.orgId(), change);
                updateLocked.countDown();
                awaitCompletionLatch(releaseUpdate);
            }));
            try {
                assertTrue(updateLocked.await(15, TimeUnit.SECONDS));
                var callback = executor.submit(() -> {
                    completeAndAssert(fixture, authentication, false);
                    return Boolean.TRUE;
                });
                assertTrue(callbackAttemptedLock.await(15, TimeUnit.SECONDS));
                releaseUpdate.countDown();
                update.get(15, TimeUnit.SECONDS);
                assertTrue(callback.get(15, TimeUnit.SECONDS));
            } finally {
                releaseUpdate.countDown();
            }
        }
    }

    private CompletionFixture completionFixture(boolean returning) {
        TestTransaction.end();
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        String unique = UUID.randomUUID().toString();
        Organization organization = new Organization();
        organization.setName("Callback completion");
        organization.setSlug("callback-" + unique);
        organizationMapper.insert(organization);
        Workspace target = new Workspace();
        target.setOrgId(organization.getId());
        target.setName("Callback completion");
        target.setSlug("callback-" + unique);
        workspaceMapper.insert(target);
        String domain = unique + ".example.test";
        String email = "callback@" + domain;
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(organization.getId());
        connection.setProtocol(PROVIDER);
        connection.setEnabled(true);
        connection.setJitWorkspaceId(target.getId());
        connection.setDefaultRole("member");
        connection.setOidcIssuer(ISSUER);
        connection.setOidcClientId("client-abc");
        connection.setOidcScopes("openid,email,profile");
        ssoConnectionMapper.upsert(connection);
        ssoDomainMapper.insert(domain, organization.getId());
        if (returning) {
            User linked = provisionlessUser(email);
            workspaceMapper.addMember(target.getId(), linked.getId(), "member");
            FederatedIdentity identity = new FederatedIdentity();
            identity.setOrgId(organization.getId());
            identity.setUserId(linked.getId());
            identity.setProvider(PROVIDER);
            identity.setIssuer(ISSUER);
            identity.setExternalSubject(unique);
            federatedIdentityMapper.insert(identity);
        }
        return new CompletionFixture(organization.getId(), email, unique, returning);
    }

    private static OAuth2AuthenticationToken completionAuthentication(CompletionFixture fixture) {
        Instant now = Instant.now();
        OidcIdToken token = new OidcIdToken("verified-token", now, now.plusSeconds(300), Map.of(
                "iss", ISSUER, "sub", fixture.subject(), "aud", List.of("client-abc"),
                "email", fixture.email(), "email_verified", true, "name", "Callback user"));
        DefaultOidcUser user = new DefaultOidcUser(List.of(), token);
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "org-" + fixture.orgId());
    }

    private void changeConnection(int organizationId, String change) {
        SsoConnection connection = ssoConnectionMapper.findByOrg(organizationId);
        assertNotNull(connection);
        switch (change) {
            case "disabled" -> connection.setEnabled(false);
            case "issuer" -> connection.setOidcIssuer(ISSUER + "/replacement");
            case "client" -> connection.setOidcClientId("replacement-client");
            case "protocol" -> connection.setProtocol("saml");
            case "unchanged" -> { }
            default -> throw new IllegalArgumentException(change);
        }
        ssoConnectionMapper.upsert(connection);
    }

    private void completeAndAssert(CompletionFixture fixture, OAuth2AuthenticationToken authentication,
            boolean accepted) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        try {
            successHandler.onAuthenticationSuccess(request, response, authentication);
            FederatedIdentity identity = federatedIdentityMapper.findByOrgProviderIssuerSubject(
                    fixture.orgId(), PROVIDER, ISSUER, fixture.subject());
            if (accepted) {
                assertEquals(mailProperties.getAppBaseUrl() + "/dashboard", response.getRedirectedUrl());
                assertInstanceOf(User.class, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
                assertNotNull(userMapper.getUserByEmail(fixture.email()));
                assertNotNull(identity);
            } else {
                assertEquals(mailProperties.getAppBaseUrl() + "/auth/login?sso_error=1", response.getRedirectedUrl());
                assertNull(SecurityContextHolder.getContext().getAuthentication());
                assertNull(request.getSession().getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
                if (fixture.returning()) {
                    assertNotNull(identity);
                    assertNull(identity.getLastLoginAt());
                } else {
                    assertNull(userMapper.getUserByEmail(fixture.email()));
                    assertNull(identity);
                }
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void awaitCompletionLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record CompletionFixture(int orgId, String email, String subject, boolean returning) {
    }
}
