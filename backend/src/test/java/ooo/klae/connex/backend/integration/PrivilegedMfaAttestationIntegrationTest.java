package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MfaAttestationCodeDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.MfaAttestationRefusedException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.integration.SoftwarePasskeys.SoftwarePasskey;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.PrivilegedMfaAttestationRedemption;
import ooo.klae.connex.backend.services.PrivilegedMfaAttestationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
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
    @Autowired private PlatformTransactionManager transactionManager;

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
    void revokingAnOpenCodeRecordsTheRevokerAndNoRedemptionCanUseItAfterwards() {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);
        steppedUp(owner);
        attestationService.revokeForWorkspace(workspace.getId(), owner.getId(), member.getId());

        assertEquals(owner.getId(), jdbcTemplate.queryForObject(
            "SELECT revoked_by_user_id FROM privileged_mfa_attestation_grant"
                + " WHERE grantee_user_id = ? AND revoked_at IS NOT NULL",
            Integer.class, member.getId()));
        assertEquals("grant_unusable", refusalOf(member, passkey, issued.code()));
        assertThrows(MfaAttestationRefusedException.class,
            () -> redeem(member, passkey, sessionOf(member), issued.code()));
        assertEquals(List.of(), coverage(passkey.rowId()));
        assertEquals(List.of(0), attemptsOnEveryGrant(member));
    }

    @Test
    void anExpiredCodeIsRefusedWithoutCoverageAndItsRefusalsAreNeverCounted() {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);
        jdbcTemplate.update(
            "UPDATE privileged_mfa_attestation_grant SET expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)"
                + " WHERE grantee_user_id = ?", member.getId());

        assertEquals("grant_unusable", refusalOf(member, passkey, issued.code()));
        assertThrows(MfaAttestationRefusedException.class,
            () -> redeem(member, passkey, sessionOf(member), issued.code()));
        assertEquals(List.of(), coverage(passkey.rowId()));
        assertEquals(List.of(0), attemptsOnEveryGrant(member));
        assertEquals(1, openGrants(member));
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
    void aDeletionReservationCommittedWhileIssuanceWaitsOnTheAccountRefusesTheCode() throws Exception {
        List<String> outcomes = commitWhileBlocked(
            () -> userMapper.reserveAccountDeletion(member.getId(), UUID.randomUUID().toString()),
            "app_user", member.getId(), TransactionDefinition.ISOLATION_DEFAULT,
            List.of(issuanceAs(owner, () -> attestationService.issueForWorkspace(
                workspace.getId(), owner.getId(), member.getId()))));

        assertEquals(List.of("User is not a member of this workspace"), outcomes);
        assertEquals(0, openGrants(member));
    }

    @Test
    void aTeardownFenceCommittedWhileAnOrganizationIssuanceWaitsRefusesTheCode() throws Exception {
        User orgAdmin = newUser();
        orgMemberMapper.addMember(organization.getId(), owner.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), orgAdmin.getId(), "admin");

        List<String> outcomes = commitWhileBlocked(
            () -> jdbcTemplate.update(
                "UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", organization.getId()),
            "organization", organization.getId(), TransactionDefinition.ISOLATION_DEFAULT,
            List.of(issuanceAs(owner, () -> attestationService.issueForOrganization(
                organization.getId(), owner.getId(), orgAdmin.getId()))));

        assertEquals(List.of("Requires the organization owner role"), outcomes);
        assertEquals(0, openGrants(orgAdmin));
    }

    @Test
    void twoRedemptionsBlockedOnTheSameAuthorityLocksClaimTheCodeExactlyOnce() throws Exception {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);
        int epoch = userMapper.currentSessionEpoch(member.getId());
        int firstAccount = Math.min(owner.getId(), member.getId());
        Supplier<String> attempt = redemptionAs(member, epoch, passkey, issued.code());

        List<String> outcomes = new ArrayList<>(commitWhileBlocked(
            () -> userMapper.lockById(firstAccount),
            "app_user", firstAccount, TransactionDefinition.ISOLATION_READ_COMMITTED,
            List.of(attempt, attempt)));
        outcomes.sort(String::compareTo);

        assertEquals(List.of("claimed", "grant_unusable"), outcomes);
        assertEquals(1, coverage(passkey.rowId()).size());
    }

    @Test
    void aSessionRevokedWhileARedemptionWaitsOnTheAccountIsRefusedUnderTheLocks() throws Exception {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);
        int epoch = userMapper.currentSessionEpoch(member.getId());

        List<String> outcomes = commitWhileBlocked(
            () -> userMapper.bumpSessionEpoch(member.getId()),
            "app_user", member.getId(), TransactionDefinition.ISOLATION_READ_COMMITTED,
            List.of(redemptionAs(member, epoch, passkey, issued.code())));

        assertEquals(List.of("session_not_current"), outcomes);
        assertEquals(List.of(), coverage(passkey.rowId()));
        assertEquals(1, openGrants(member));
    }

    @Test
    void aPasskeyRemovedWhileARedemptionWaitsOnTheAccountIsNeverAttested() throws Exception {
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = issueThroughWorkspace(owner, member);
        int epoch = userMapper.currentSessionEpoch(member.getId());

        List<String> outcomes = commitWhileBlocked(
            () -> {
                userMapper.lockById(member.getId());
                jdbcTemplate.update("DELETE FROM webauthn_credential WHERE id = ?", passkey.rowId());
            },
            "app_user", member.getId(), TransactionDefinition.ISOLATION_READ_COMMITTED,
            List.of(redemptionAs(member, epoch, passkey, issued.code())));

        assertEquals(List.of("credential_not_owned"), outcomes);
        assertEquals(1, openGrants(member));
    }

    private record Coverage(int orgId, String source, Long grantId) {
    }

    /**
     * Holds {@code change} uncommitted in a transaction of its own, then starts each contender in
     * its own transaction of {@code isolation} and waits until MySQL reports it blocked on the held
     * {@code table} row. Only then does the change commit. Returns each contender's outcome in
     * order: what it returned, or the reason or message it was refused with.
     */
    private List<String> commitWhileBlocked(
            Runnable change,
            String table,
            int heldRowId,
            int isolation,
            List<Supplier<String>> contenders) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders.size() + 1);
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    change.run();
                    held.countDown();
                    await(release);
                }));
            assertTrue(held.await(30, TimeUnit.SECONDS), "the change was never held");
            List<Future<String>> pending = new ArrayList<>();
            for (Supplier<String> contender : contenders) {
                AtomicLong connection = new AtomicLong();
                CountDownLatch started = new CountDownLatch(1);
                pending.add(executor.submit(() -> contend(isolation, connection, started, contender)));
                assertTrue(started.await(30, TimeUnit.SECONDS), "a contender never started");
                MySqlLockWaitProbe.awaitExclusiveRecordLock(
                    jdbcTemplate, connection.get(), table, Integer.toString(heldRowId));
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<String> outcomes = new ArrayList<>();
            for (Future<String> outcome : pending) {
                outcomes.add(outcome.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private String contend(
            int isolation, AtomicLong connection, CountDownLatch started, Supplier<String> contender) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(isolation);
        try {
            return transaction.execute(status -> {
                connection.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                started.countDown();
                return contender.get();
            });
        } catch (PrivilegedMfaAttestationService.AttestationRefusal refusal) {
            return refusal.reason();
        } catch (ResourceNotFoundException | ForbiddenException refused) {
            return refused.getMessage();
        }
    }

    private Supplier<String> issuanceAs(User grantor, Supplier<MfaAttestationCodeDto> issue) {
        return () -> {
            steppedUp(grantor);
            try {
                issue.get();
                return "issued";
            } finally {
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        };
    }

    private Supplier<String> redemptionAs(User grantee, int epoch, SoftwarePasskey passkey, String code) {
        return () -> {
            SoftwarePasskeys.authenticate(grantee);
            try {
                attestationService.redeem(grantee.getId(), epoch, passkey.rowId(), code);
                return "claimed";
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private String refusalOf(User grantee, SoftwarePasskey passkey, String code) {
        SoftwarePasskeys.authenticate(grantee);
        int epoch = userMapper.currentSessionEpoch(grantee.getId());
        return assertThrows(PrivilegedMfaAttestationService.AttestationRefusal.class,
            () -> attestationService.redeem(grantee.getId(), epoch, passkey.rowId(), code)).reason();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("the held change was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while holding the change", interrupted);
        }
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

    private List<Integer> attemptsOnEveryGrant(User grantee) {
        return jdbcTemplate.queryForList(
            "SELECT failed_attempts FROM privileged_mfa_attestation_grant WHERE grantee_user_id = ? ORDER BY id",
            Integer.class, grantee.getId());
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
