package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MfaAttestationCodeDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.MfaAttestationRefusedException;
import ooo.klae.connex.backend.integration.SoftwarePasskeys.SoftwarePasskey;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.PrivilegedMfaAttestationRedemption;
import ooo.klae.connex.backend.services.PrivilegedMfaAttestationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.webauthn.EnrollmentEvidence;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Proves grantor-issued privileged MFA attestation codes against the real schema, real authority
 * locks and real passkey ceremonies (#1534, slice 2): who can issue, that a code only ever works
 * for its grantee and its organization, how refusals are counted, and that a redemption re-checks
 * the authority, the session and the passkey under its locks.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PrivilegedMfaAttestationIntegrationTest {
    private static final EnrollmentEvidence NO_EVIDENCE = new EnrollmentEvidence(null, null);
    private static final String WRONG_CODE = "ZZZZ-ZZZZ-ZZZZ-ZZZZ";

    @Autowired private PrivilegedMfaAttestationService attestationService;
    @Autowired private PrivilegedMfaAttestationRedemption redemption;
    @Autowired private WebAuthnService webAuthnService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private UserMapper userMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

    private Organization organization;
    private Workspace workspace;
    private User owner;
    private User member;

    @BeforeEach
    void setUp() {
        organization = newOrganization();
        workspace = newWorkspace(organization);
        owner = newUser();
        member = newUser();
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        for (int workspaceId : workspaceIds) {
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (int organizationId : organizationIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
        }
        for (int userId : userIds) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    void aWorkspaceOwnersCodeAttestsTheMembersSigningPasskeyInTheOrganization() {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);

        MockHttpServletRequest session = sessionOf(member);
        int orgId = redeem(member, passkey, session, issued.code());

        assertEquals(organization.getId(), orgId);
        assertEquals(List.of(new Coverage(organization.getId(), "GRANTOR", grantIdFor(member))),
            coverage(passkey.rowId()));
        assertEquals(passkey.rowId(), jdbcTemplate.queryForObject(
            "SELECT redeemed_credential_row_id FROM privileged_mfa_attestation_grant WHERE grantee_user_id = ?",
            Integer.class, member.getId()));
        assertNotNull(sessionSecurityService.recentStepUpProof(session.getSession(false), member.getId()));
        assertEquals(passkey.rowId(), sessionSecurityService
            .recentStepUpProof(session.getSession(false), member.getId()).credentialRowId());
    }

    @Test
    void aCodeOnlyEverWorksForItsGranteeAndAnotherAccountsRefusalCountsAgainstItsOwnCodesOnly() {
        User other = newUser();
        workspaceMapper.addMember(workspace.getId(), other.getId(), "member");
        SoftwarePasskey otherPasskey = register(other);
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);

        assertThrows(MfaAttestationRefusedException.class,
            () -> redeem(other, otherPasskey, sessionOf(other), issued.code()));

        assertEquals(List.of(), coverage(otherPasskey.rowId()));
        assertEquals(0, failedAttempts(member));
        redeem(member, passkey, sessionOf(member), issued.code());
        assertEquals("GRANTOR", coverage(passkey.rowId()).getFirst().source());
    }

    @Test
    void fiveRefusedRedemptionsExhaustTheGrantAndTheCountSurvivesEachRollback() {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThrows(MfaAttestationRefusedException.class,
                () -> redeem(member, passkey, sessionOf(member), WRONG_CODE));
            assertEquals(attempt, failedAttempts(member));
        }

        assertThrows(MfaAttestationRefusedException.class,
            () -> redeem(member, passkey, sessionOf(member), issued.code()));
        assertEquals(5, failedAttempts(member));
        assertEquals(List.of(), coverage(passkey.rowId()));
    }

    @Test
    void aNewCodeSupersedesTheOpenOneAndRevokingNeverRemovesRedeemedCoverage() {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto first = issueThroughWorkspace(owner, member);
        MfaAttestationCodeDto second = issueThroughWorkspace(owner, member);

        assertThrows(MfaAttestationRefusedException.class,
            () -> redeem(member, passkey, sessionOf(member), first.code()));
        redeem(member, passkey, sessionOf(member), second.code());
        steppedUp(owner);
        attestationService.revokeForWorkspace(workspace.getId(), owner.getId(), member.getId());

        assertEquals("GRANTOR", coverage(passkey.rowId()).getFirst().source());
        assertEquals(0, openGrants(member));
    }

    @Test
    void aGrantorWhoLostTheAuthorityCannotStandBehindARedemption() {
        User admin = newUser();
        workspaceMapper.addMember(workspace.getId(), admin.getId(), "admin");
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(admin, member);
        workspaceMapper.updateMemberRole(workspace.getId(), admin.getId(), "member");

        assertThrows(MfaAttestationRefusedException.class,
            () -> redeem(member, passkey, sessionOf(member), issued.code()));

        assertEquals(List.of(), coverage(passkey.rowId()));
        assertEquals(1, failedAttempts(member));
    }

    @Test
    void aMemberCannotIssueACodeForAnyoneAndNobodyCanIssueOneForThemselves() {
        User peer = newUser();
        workspaceMapper.addMember(workspace.getId(), peer.getId(), "member");

        steppedUp(member);
        ForbiddenException notAuthorized = assertThrows(ForbiddenException.class,
            () -> attestationService.issueForWorkspace(workspace.getId(), member.getId(), peer.getId()));
        steppedUp(owner);
        ForbiddenException self = assertThrows(ForbiddenException.class,
            () -> attestationService.issueForWorkspace(workspace.getId(), owner.getId(), owner.getId()));

        assertEquals("Requires the MEMBER_MANAGE permission in this workspace", notAuthorized.getMessage());
        assertEquals("An attestation code cannot be issued to yourself", self.getMessage());

        assertEquals(0, openGrants(peer));
        assertEquals(0, openGrants(owner));
    }

    @Test
    void anOrganizationOwnersCodeAttestsAnOrgAdminWithNoWorkspace() {
        User orgAdmin = newUser();
        orgMemberMapper.addMember(organization.getId(), owner.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), orgAdmin.getId(), "admin");
        SoftwarePasskey passkey = register(orgAdmin);
        steppedUp(owner);
        MfaAttestationCodeDto issued =
            attestationService.issueForOrganization(organization.getId(), owner.getId(), orgAdmin.getId());

        redeem(orgAdmin, passkey, sessionOf(orgAdmin), issued.code());

        assertEquals(List.of(new Coverage(organization.getId(), "GRANTOR", grantIdFor(orgAdmin))),
            coverage(passkey.rowId()));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT workspace_id FROM privileged_mfa_attestation_grant WHERE grantee_user_id = ?",
            Integer.class, orgAdmin.getId()));
    }

    @Test
    void aRedemptionReplacesInheritedCoverageButLeavesADirectRowExactlyAsItWas() {
        SoftwarePasskey inherited = register(member);
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'INHERITED')", inherited.rowId(), organization.getId());
        redeem(member, inherited, sessionOf(member), issueThroughWorkspace(owner, member).code());

        User founder = newUser();
        workspaceMapper.addMember(workspace.getId(), founder.getId(), "member");
        SoftwarePasskey direct = register(founder);
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'FOUNDER')", direct.rowId(), organization.getId());
        redeem(founder, direct, sessionOf(founder), issueThroughWorkspace(owner, founder).code());

        assertEquals(List.of(new Coverage(organization.getId(), "GRANTOR", grantIdFor(member))),
            coverage(inherited.rowId()));
        assertEquals(List.of(new Coverage(organization.getId(), "FOUNDER", null)), coverage(direct.rowId()));
    }

    @Test
    void twoConcurrentRedemptionsOfOneCodeClaimItExactlyOnce() throws Exception {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);
        int epoch = userMapper.currentSessionEpoch(member.getId());
        Callable<String> attempt = () -> {
            SoftwarePasskeys.authenticate(member);
            try {
                attestationService.redeem(member.getId(), epoch, passkey.rowId(), issued.code());
                return "claimed";
            } catch (PrivilegedMfaAttestationService.AttestationRefusal refusal) {
                return refusal.reason();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(attempt);
            Future<String> second = executor.submit(attempt);
            List<String> outcomes = new ArrayList<>(List.of(
                first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)));
            outcomes.sort(String::compareTo);

            assertEquals(List.of("claimed", "grant_unusable"), outcomes);
            assertEquals(1, coverage(passkey.rowId()).size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void aRedemptionReChecksTheSessionAndThePasskeyUnderItsLocks() {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);
        int epoch = userMapper.currentSessionEpoch(member.getId());

        PrivilegedMfaAttestationService.AttestationRefusal stale = assertThrows(
            PrivilegedMfaAttestationService.AttestationRefusal.class,
            () -> attestationService.redeem(member.getId(), epoch - 1, passkey.rowId(), issued.code()));
        jdbcTemplate.update("DELETE FROM webauthn_credential WHERE id = ?", passkey.rowId());
        PrivilegedMfaAttestationService.AttestationRefusal gone = assertThrows(
            PrivilegedMfaAttestationService.AttestationRefusal.class,
            () -> attestationService.redeem(member.getId(), epoch, passkey.rowId(), issued.code()));

        assertEquals("session_not_current", stale.reason());
        assertEquals("credential_not_owned", gone.reason());
        assertEquals(1, openGrants(member));
    }

    private record Coverage(int orgId, String source, Long grantId) {
    }

    private MfaAttestationCodeDto issueThroughWorkspace(User grantor, User grantee) {
        steppedUp(grantor);
        return attestationService.issueForWorkspace(workspace.getId(), grantor.getId(), grantee.getId());
    }

    private int redeem(User grantee, SoftwarePasskey passkey, MockHttpServletRequest session, String code) {
        Authentication auth = SoftwarePasskeys.authenticate(grantee);
        PublicKeyCredentialRequestOptions options = webAuthnService.createStepUpOptions(auth);
        return redemption.redeem(
            session, auth, grantee, options, SoftwarePasskeys.sign(passkey, options), code).orgId();
    }

    private MockHttpServletRequest sessionOf(User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionSecurityService.stampSessionEpoch(request, userMapper.currentSessionEpoch(user.getId()));
        return request;
    }

    private void steppedUp(User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionSecurityService.markStepUp(request, user.getId(), 0);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SoftwarePasskeys.authenticate(user);
    }

    private SoftwarePasskey register(User user) {
        SoftwarePasskey passkey = SoftwarePasskeys.register(webAuthnService, userMapper, user, NO_EVIDENCE, "Attest");
        SecurityContextHolder.clearContext();
        return passkey;
    }

    private List<Coverage> coverage(int credentialRowId) {
        return jdbcTemplate.query(
            "SELECT org_id, source, grant_id FROM privileged_credential_attestation"
                + " WHERE credential_row_id = ? ORDER BY org_id",
            (resultSet, rowNumber) -> new Coverage(
                resultSet.getInt("org_id"),
                resultSet.getString("source"),
                resultSet.getObject("grant_id", Long.class)),
            credentialRowId);
    }

    private Long grantIdFor(User grantee) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM privileged_mfa_attestation_grant WHERE grantee_user_id = ? AND redeemed_at IS NOT NULL",
            Long.class, grantee.getId());
    }

    private int failedAttempts(User grantee) {
        Integer attempts = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(failed_attempts), 0) FROM privileged_mfa_attestation_grant"
                + " WHERE grantee_user_id = ? AND revoked_at IS NULL",
            Integer.class, grantee.getId());
        return attempts == null ? 0 : attempts;
    }

    private int openGrants(User grantee) {
        Integer open = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM privileged_mfa_attestation_grant"
                + " WHERE grantee_user_id = ? AND redeemed_at IS NULL AND revoked_at IS NULL",
            Integer.class, grantee.getId());
        return open == null ? 0 : open;
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization created = new Organization();
        created.setName("Attestation Org " + suffix);
        created.setSlug("attestation-org-" + suffix);
        organizationMapper.insert(created);
        organizationIds.add(created.getId());
        return created;
    }

    private Workspace newWorkspace(Organization parent) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace created = new Workspace();
        created.setOrgId(parent.getId());
        created.setName("Attestation Workspace " + suffix);
        created.setSlug("attestation-ws-" + suffix);
        created.setTimezone("UTC");
        workspaceMapper.insert(created);
        workspaceIds.add(created.getId());
        return created;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User created = new User();
        created.setUsername("attest_" + suffix);
        created.setDisplayName("Attest " + suffix);
        created.setEmail("attest-" + suffix + "@example.com");
        created.setPasswordHash("unused");
        created.setTimezone("UTC");
        userMapper.insert(created);
        userIds.add(created.getId());
        return userMapper.getUserById(created.getId());
    }
}
