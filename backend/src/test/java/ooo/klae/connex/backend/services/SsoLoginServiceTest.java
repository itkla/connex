package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.OrgAllowedDomainMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;

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
                        "newcomer@" + OWNED_DOMAIN, true, orgId, "Newcomer"),
                "SSO must not provision a member the org's own domain ceiling forbids, "
                        + "even though the domain is in the sso_domain routing list");
        assertNull(userMapper.getUserByEmail("newcomer@" + OWNED_DOMAIN),
                "a refused SSO login must write nothing");
    }

    @Test
    void resolve_provisionsWhenTheOrgCeilingAllowsTheDomain() {
        orgAllowedDomainMapper.add(orgId, OWNED_DOMAIN);

        SsoLoginResult result = ssoLoginService.resolve(PROVIDER, ISSUER, "sub-allowed",
                "welcome@" + OWNED_DOMAIN, true, orgId, "Welcome");

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
                "No JIT Workspace"));
        assertNull(userMapper.getUserByEmail("no-jit-workspace@" + OWNED_DOMAIN));
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
                linked.getEmail(), true, orgId, "Linked User");

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
                "outsider@notowned.example.org", true, orgId, "Outsider"),
                "an identity minted for another org must not sign in through this org, and the "
                        + "foreign email domain is refused");
    }

    @Test
    void verifiedEmailMatchingPasswordAccount_requiresLinkAndWritesNothing() {
        User password = newUser();
        assertNotNull(password.getPassword(), "the fixture account must have a password");

        SsoLoginResult result = ssoLoginService.resolve(PROVIDER, ISSUER, "sub-collision",
                password.getEmail(), true, orgId, "Password User");

        SsoLoginResult.LinkRequired link = assertInstanceOf(SsoLoginResult.LinkRequired.class, result);
        assertEquals(password.getId(), link.existingUserId());
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-collision"),
                "no identity may be minted for a link-required outcome (no auto-link, no session)");
    }

    @Test
    void newVerifiedEmailInOwnedDomain_provisionsMemberAndIdentity() {
        String email = "newcomer@" + OWNED_DOMAIN;

        SsoLoginResult result = ssoLoginService.resolve(PROVIDER, ISSUER, "sub-newcomer",
                email, true, orgId, "New Comer");

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
                ssoLoginService.resolve(PROVIDER, ISSUER, "sub-intruder", email, true, orgId, "Intruder"));

        assertNull(userMapper.getUserByEmail(email),
                "a domain the org does not own must not provision an account");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-intruder"),
                "a domain the org does not own must not mint an identity");
    }

    @Test
    void unverifiedEmail_isRefusedWithNothingWritten() {
        String email = "unverified@" + OWNED_DOMAIN;

        assertThrows(ForbiddenException.class, () ->
                ssoLoginService.resolve(PROVIDER, ISSUER, "sub-unverified", email, false, orgId, "Unverified"));

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
                victim.getEmail(), true, orgId, "Victim"),
                "a passwordless account already federated to another organization must not be claimed");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-victim-claim"),
                "no identity may be minted when refusing a cross-org claim");
    }
}
