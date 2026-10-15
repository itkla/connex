package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MfaAttestationCodeDto;
import ooo.klae.connex.backend.integration.SoftwarePasskeys.SoftwarePasskey;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.PrivilegedMfaAttestationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.UserDeletionTransaction;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.webauthn.EnrollmentEvidence;
import ooo.klae.connex.backend.webauthn.WebAuthnService;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;

/**
 * Pins owner decisions 1 and 3 of #1534 against real locks and commits. A new organization
 * membership goes only to an existing tenant, read after the target's account row. Leaving an
 * organization serializes with every other writer of its owner rows, so an organization always keeps
 * an owner and departed authority backs no grant. Both exits audit strictly, so an exit that cannot
 * be recorded does not happen.
 *
 * <p>Every contender calls the production entry point itself, with no surrounding transaction, so
 * each runs at the isolation its own annotation declares. A spied {@link UserMapper} records the
 * contender's connection inside that transaction just before its first account lock, which lets the
 * lock probe confirm the contender is really waiting on the held row before it is released.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrganizationTenancyAndExitIntegrationTest {
    private static final String NOT_A_TENANT =
        "Only active members of a workspace in this organization can be added to it";
    private static final String LAST_OWNER = "An organization must keep at least one owner";
    private static final String NOT_AN_OWNER = "Requires the organization owner role";
    private static final String NOT_A_MEMBER = "You are not a member of this organization";
    private static final String DELETION_REFUSED = "Transfer organization ownership before deleting your account";
    private static final String DONE = "done";

    private static final ThreadLocal<ConnectionProbe> CONNECTION_PROBE = new ThreadLocal<>();

    @Autowired private OrgMemberService orgMemberService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private PrivilegedMfaAttestationService attestationService;
    @Autowired private UserDeletionTransaction userDeletionTransaction;
    @Autowired private WebAuthnService webAuthnService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private UserMapper userMapper;
    @MockitoSpyBean private AuditService auditService;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void recordContenderConnectionsAtTheirFirstAccountLock() {
        UserMapper real = sqlSessionTemplate.getMapper(UserMapper.class);
        doAnswer(invocation -> {
            recordConnection();
            return real.lockByIdForShare(invocation.getArgument(0));
        }).when(userMapper).lockByIdForShare(anyInt());
        doAnswer(invocation -> {
            recordConnection();
            return real.lockById(invocation.getArgument(0));
        }).when(userMapper).lockById(anyInt());
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

    /**
     * A removal that commits while an add waits on the target's account row is seen by the
     * tenancy read, so the add is refused rather than granted to someone who just left. The add
     * runs at its own isolation: under the default one, the read made before the wait would fix the
     * snapshot and the add would see the removed membership.
     */
    @Test
    void aWorkspaceRemovalCommittedWhileAnAddWaitsRefusesTheAdd() throws Exception {
        Organization organization = newOrganization();
        Workspace workspace = newWorkspace(organization);
        User owner = newUser();
        User target = newUser();
        orgMemberMapper.addMember(organization.getId(), owner.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), target.getId(), "member");

        List<String> outcomes = commitWhileBlocked(
            () -> {
                userMapper.lockById(target.getId());
                workspaceMapper.removeMember(workspace.getId(), target.getId());
            },
            "app_user", target.getId(), true,
            List.of(() -> steppedUpAs(owner, () -> { orgMemberService.setMember(
                organization.getId(), owner.getId(), target.getId(), "admin"); })));

        assertEquals(List.of(NOT_A_TENANT), outcomes);
        assertNull(orgMemberMapper.getRole(organization.getId(), target.getId()));
    }

    /** The add by email runs at its own isolation too, and refuses the same way. */
    @Test
    void aWorkspaceRemovalCommittedWhileAnAddByEmailWaitsRefusesTheAdd() throws Exception {
        Organization organization = newOrganization();
        Workspace workspace = newWorkspace(organization);
        User owner = newUser();
        User target = newUser();
        orgMemberMapper.addMember(organization.getId(), owner.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), target.getId(), "member");

        List<String> outcomes = commitWhileBlocked(
            () -> {
                userMapper.lockById(target.getId());
                workspaceMapper.removeMember(workspace.getId(), target.getId());
            },
            "app_user", target.getId(), true,
            List.of(() -> steppedUpAs(owner, () -> { orgMemberService.setMemberByEmail(
                organization.getId(), owner.getId(), target.getEmail(), "admin"); })));

        assertEquals(List.of(NOT_A_TENANT), outcomes);
        assertNull(orgMemberMapper.getRole(organization.getId(), target.getId()));
    }

    /** A teardown fence committed while an add or a leave waits on the organization refuses both. */
    @Test
    void aTeardownFenceCommittedWhileAnAddAndALeaveWaitRefusesBoth() throws Exception {
        Organization organization = newOrganization();
        Workspace workspace = newWorkspace(organization);
        User owner = newUser();
        User tenant = newUser();
        User admin = newUser();
        orgMemberMapper.addMember(organization.getId(), owner.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), admin.getId(), "admin");
        workspaceMapper.addMember(workspace.getId(), tenant.getId(), "member");

        List<String> outcomes = commitWhileBlocked(
            () -> {
                organizationMapper.lockById(organization.getId());
                jdbcTemplate.update(
                    "UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", organization.getId());
            },
            "organization", organization.getId(), false,
            List.of(
                () -> steppedUpAs(owner, () -> { orgMemberService.setMember(
                    organization.getId(), owner.getId(), tenant.getId(), "admin"); }),
                () -> leave(organization, admin)));

        assertEquals(List.of(NOT_AN_OWNER, NOT_A_MEMBER), outcomes);
        assertEquals(0, membershipCount(organization, tenant));
        assertEquals(1, membershipCount(organization, admin));
    }

    /** Two owners leaving at once serialize on the organization, and one of them stays. */
    @Test
    void twoOwnersLeavingAtOnceLeaveTheOrganizationAnOwner() throws Exception {
        Organization organization = newOrganization();
        User first = newUser();
        User second = newUser();
        orgMemberMapper.addMember(organization.getId(), first.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), second.getId(), "owner");

        List<String> outcomes = new ArrayList<>(commitWhileBlocked(
            () -> organizationMapper.lockById(organization.getId()),
            "organization", organization.getId(), false,
            List.of(() -> leave(organization, first), () -> leave(organization, second))));
        outcomes.sort(String::compareTo);

        assertEquals(List.of(LAST_OWNER, DONE), outcomes);
        assertEquals(1, ownerCount(organization));
    }

    /**
     * An owner leaving while the real account-deletion transaction removes a co-owner serializes on
     * the organization row, so exactly one of them succeeds and an owner always remains.
     */
    @Test
    void anOwnerLeavingWhileACoOwnerIsDeletedLeavesTheOrganizationAnOwner() throws Exception {
        Organization organization = newOrganization();
        User leaving = newUser();
        User deleted = newUser();
        orgMemberMapper.addMember(organization.getId(), leaving.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), deleted.getId(), "owner");
        String reservation = UUID.randomUUID().toString();
        userDeletionTransaction.reserve(deleted.getId(), reservation);

        List<String> outcomes = commitWhileBlocked(
            () -> organizationMapper.lockById(organization.getId()),
            "organization", organization.getId(), false,
            List.of(() -> leave(organization, leaving), () -> {
                userDeletionTransaction.delete(deleted.getId(), reservation);
                return DONE;
            }));

        assertTrue(Set.of(List.of(DONE, DELETION_REFUSED), List.of(LAST_OWNER, DONE)).contains(outcomes),
            outcomes.toString());
        assertEquals(1, ownerCount(organization));
        assertEquals(DONE.equals(outcomes.get(1)), userMapper.getUserById(deleted.getId()) == null);
    }

    /**
     * Issuing a code and leaving the organization serialize on it. Either the code was issued
     * while the grantor still owned the organization, and it stops working once the grantor has
     * left, or the grantor left first and nothing was issued.
     */
    @Test
    void issuingAndLeavingAtOnceNeverLeaveAWorkingCodeFromADepartedOwner() throws Exception {
        Organization organization = newOrganization();
        User grantor = newUser();
        User coOwner = newUser();
        User member = newUser();
        orgMemberMapper.addMember(organization.getId(), grantor.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), coOwner.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), member.getId(), "admin");
        SoftwarePasskey grantorPasskey = register(grantor);
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'FOUNDER')", grantorPasskey.rowId(), organization.getId());
        SoftwarePasskey passkey = register(member);
        List<MfaAttestationCodeDto> issued = new ArrayList<>();

        List<String> outcomes = commitWhileBlocked(
            () -> organizationMapper.lockById(organization.getId()),
            "organization", organization.getId(), false,
            List.of(
                () -> steppedUpAs(grantor, grantorPasskey.rowId(), () -> {
                    issued.add(attestationService.issueForOrganization(
                        organization.getId(), grantor.getId(), member.getId()));
                    return DONE;
                }),
                () -> leave(organization, grantor)));

        assertEquals(DONE, outcomes.get(1));
        if (DONE.equals(outcomes.get(0))) {
            assertEquals(1, grantCount(member));
            assertEquals("authority_lost", refusalOf(member, passkey, issued.getFirst().code()));
        } else {
            assertEquals(NOT_AN_OWNER, outcomes.get(0));
            assertEquals(0, grantCount(member));
        }
    }

    /**
     * Redeeming a code and its grantor leaving serialize on the organization. Coverage is recorded
     * exactly when the redemption won, while the grantor still owned the organization.
     */
    @Test
    void aRedemptionRacingItsGrantorsLeaveCoversOnlyIfItWon() throws Exception {
        Organization organization = newOrganization();
        User grantor = newUser();
        User coOwner = newUser();
        User member = newUser();
        orgMemberMapper.addMember(organization.getId(), grantor.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), coOwner.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), member.getId(), "admin");
        SoftwarePasskey grantorPasskey = register(grantor);
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'FOUNDER')", grantorPasskey.rowId(), organization.getId());
        SoftwarePasskey passkey = register(member);
        MfaAttestationCodeDto issued = steppedUpAs(grantor, grantorPasskey.rowId(), () -> attestationService.issueForOrganization(
            organization.getId(), grantor.getId(), member.getId()));
        int epoch = userMapper.currentSessionEpoch(member.getId());

        List<String> outcomes = commitWhileBlocked(
            () -> organizationMapper.lockById(organization.getId()),
            "organization", organization.getId(), false,
            List.of(
                () -> asMember(member, () -> {
                    attestationService.redeem(member.getId(), epoch, passkey.rowId(), issued.code());
                    return DONE;
                }),
                () -> leave(organization, grantor)));

        assertEquals(DONE, outcomes.get(1));
        assertTrue(Set.of(DONE, "authority_lost").contains(outcomes.get(0)), outcomes.toString());
        assertEquals(DONE.equals(outcomes.get(0)) ? 1 : 0, coverageCount(passkey, organization));
    }

    @Test
    void anOrganizationExitThatCannotBeAuditedDoesNotHappen() {
        Organization organization = newOrganization();
        User leaving = newUser();
        User remaining = newUser();
        orgMemberMapper.addMember(organization.getId(), leaving.getId(), "admin");
        orgMemberMapper.addMember(organization.getId(), remaining.getId(), "owner");
        doThrow(new IllegalStateException("audit unavailable")).when(auditService).recordStrictScoped(
            eq("org.member.leave"), any(), any(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> orgMemberService.leave(organization.getId(), leaving.getId()));

        assertEquals("admin", orgMemberMapper.getRole(organization.getId(), leaving.getId()));
    }

    /**
     * Leaving a workspace through the endpoint's own path rolls back as one unit when its audit
     * fails: the membership and the remembered selection both stay as they were.
     */
    @Test
    void aWorkspaceExitThatCannotBeAuditedRollsBackTheLeaveAndTheNextSelection() {
        Organization organization = newOrganization();
        Workspace leaving = newWorkspace(organization);
        Workspace next = newWorkspace(organization);
        User member = newUser();
        workspaceMapper.addMember(leaving.getId(), member.getId(), "member");
        workspaceMapper.addMember(next.getId(), member.getId(), "member");
        workspaceMapper.setLastActiveWorkspaceId(member.getId(), leaving.getId());
        doThrow(new IllegalStateException("audit unavailable")).when(auditService).recordStrictScoped(
            eq("workspace.member.leave"), any(), any(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> asMember(member,
            () -> workspaceService.leaveWorkspaceAndSelectNext(leaving.getId(), member.getId())));

        assertEquals("member", workspaceMapper.getRole(leaving.getId(), member.getId()));
        assertEquals(leaving.getId(), workspaceMapper.getLastActiveWorkspaceId(member.getId()));
    }

    private void recordConnection() {
        ConnectionProbe probe = CONNECTION_PROBE.get();
        if (probe != null && probe.connectionId().get() == 0) {
            Long connectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
            probe.connectionId().set(connectionId == null ? 0 : connectionId);
            probe.known().countDown();
        }
    }

    private String leave(Organization organization, User member) {
        return asMember(member, () -> {
            orgMemberService.leave(organization.getId(), member.getId());
            return DONE;
        });
    }

    private <T> T steppedUpAs(User user, Supplier<T> action) {
        int credential = OwnershipOfferTestFixtures.approvedPasskey(jdbcTemplate, user);
        return steppedUpAs(user, credential, action);
    }

    private <T> T steppedUpAs(User user, int credentialRowId, Supplier<T> action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionSecurityService.markStepUp(request, user.getId(), credentialRowId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SoftwarePasskeys.authenticate(user);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private String steppedUpAs(User user, Runnable action) {
        return steppedUpAs(user, () -> {
            action.run();
            return DONE;
        });
    }

    private <T> T asMember(User user, Supplier<T> action) {
        SoftwarePasskeys.authenticate(user);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private SoftwarePasskey register(User user) {
        SoftwarePasskey passkey = SoftwarePasskeys.register(
            webAuthnService, userMapper, user, new EnrollmentEvidence(null, null), "Exit");
        SecurityContextHolder.clearContext();
        return passkey;
    }

    private String refusalOf(User grantee, SoftwarePasskey passkey, String code) {
        return asMember(grantee, () -> {
            int epoch = userMapper.currentSessionEpoch(grantee.getId());
            return assertThrows(PrivilegedMfaAttestationService.AttestationRefusal.class,
                () -> attestationService.redeem(grantee.getId(), epoch, passkey.rowId(), code)).reason();
        });
    }

    private List<String> commitWhileBlocked(
            Runnable change,
            String table,
            int heldRowId,
            boolean sharedWait,
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
                ConnectionProbe probe = new ConnectionProbe(new AtomicLong(), new CountDownLatch(1));
                pending.add(executor.submit(() -> contend(probe, contender)));
                assertTrue(probe.known().await(30, TimeUnit.SECONDS), "a contender never reached its first lock");
                if (sharedWait) {
                    MySqlLockWaitProbe.awaitSharedRecordLock(
                        jdbcTemplate, probe.connectionId().get(), table, Integer.toString(heldRowId));
                } else {
                    MySqlLockWaitProbe.awaitExclusiveRecordLock(
                        jdbcTemplate, probe.connectionId().get(), table, Integer.toString(heldRowId));
                }
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

    /**
     * Runs a contender through its production entry point with no surrounding transaction, so it
     * opens its own at the isolation it declares, and reports its connection to the probe.
     */
    private static String contend(ConnectionProbe probe, Supplier<String> contender) {
        CONNECTION_PROBE.set(probe);
        try {
            return contender.get();
        } catch (RuntimeException refused) {
            return refused.getMessage();
        } finally {
            CONNECTION_PROBE.remove();
            probe.known().countDown();
        }
    }

    private int ownerCount(Organization organization) {
        return count("SELECT COUNT(*) FROM org_member WHERE org_id = ? AND org_role = 'owner'", organization.getId());
    }

    private int membershipCount(Organization organization, User user) {
        return count("SELECT COUNT(*) FROM org_member WHERE org_id = ? AND user_id = ?",
            organization.getId(), user.getId());
    }

    private int grantCount(User grantee) {
        return count("SELECT COUNT(*) FROM privileged_mfa_attestation_grant WHERE grantee_user_id = ?", grantee.getId());
    }

    private int coverageCount(SoftwarePasskey passkey, Organization organization) {
        return count("SELECT COUNT(*) FROM privileged_credential_attestation WHERE credential_row_id = ? AND org_id = ?",
            passkey.rowId(), organization.getId());
    }

    private int count(String sql, Object... arguments) {
        Integer counted = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        assertNotNull(counted);
        return counted;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The held change was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization created = new Organization();
        created.setName("Exit Org " + suffix);
        created.setSlug("exit-org-" + suffix);
        organizationMapper.insert(created);
        organizationIds.add(created.getId());
        return created;
    }

    private Workspace newWorkspace(Organization parent) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace created = new Workspace();
        created.setOrgId(parent.getId());
        created.setName("Exit Workspace " + suffix);
        created.setSlug("exit-ws-" + suffix);
        created.setTimezone("UTC");
        workspaceMapper.insert(created);
        workspaceIds.add(created.getId());
        return created;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User created = new User();
        created.setUsername("exit_" + suffix);
        created.setDisplayName("Exit " + suffix);
        created.setEmail("exit-" + suffix + "@example.com");
        created.setPasswordHash("unused");
        created.setTimezone("UTC");
        userMapper.insert(created);
        userIds.add(created.getId());
        return userMapper.getUserById(created.getId());
    }

    /**
     * Where a contender reports the connection its own transaction runs on.
     *
     * @param connectionId the MySQL connection id, or zero until the contender reaches a lock
     * @param known released once the contender reports, or finishes without reaching a lock
     */
    private record ConnectionProbe(AtomicLong connectionId, CountDownLatch known) {
    }
}
