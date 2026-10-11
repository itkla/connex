package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import ooo.klae.connex.backend.integration.SoftwarePasskeys.SoftwarePasskey;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.PrivilegedMfaAttestationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.webauthn.EnrollmentEvidence;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Pins owner decisions 1 and 3 of #1534 against real locks and commits. A new organization
 * membership goes only to an existing tenant, read after the target's account row. Leaving an
 * organization serializes with every other writer of its owner rows, so an organization always keeps
 * an owner and departed authority backs no grant. Both exits audit strictly, so an exit that cannot
 * be recorded does not happen.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrganizationTenancyAndExitIntegrationTest {
    private static final String NOT_A_TENANT =
        "Only active members of a workspace in this organization can be added to it";
    private static final String LAST_OWNER = "An organization must keep at least one owner";
    private static final String DONE = "done";

    @Autowired private OrgMemberService orgMemberService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private PrivilegedMfaAttestationService attestationService;
    @Autowired private WebAuthnService webAuthnService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private UserMapper userMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AuditService auditService;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

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
     * tenancy read, so the add is refused rather than granted to someone who just left.
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
            List.of(() -> steppedUpAs(owner, () -> orgMemberService.setMember(
                organization.getId(), owner.getId(), target.getId(), "admin"))));

        assertEquals(List.of(NOT_A_TENANT), outcomes);
        assertNull(orgMemberMapper.getRole(organization.getId(), target.getId()));
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
     * An owner leaving while a co-owner deletes their account cannot leave the organization
     * ownerless either way round: account deletion takes the same organization row first.
     */
    @Test
    void anOwnerLeavingWhileACoOwnerIsDeletedLeavesTheOrganizationAnOwner() throws Exception {
        Organization organization = newOrganization();
        User leaving = newUser();
        User deleted = newUser();
        orgMemberMapper.addMember(organization.getId(), leaving.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), deleted.getId(), "owner");

        List<String> outcomes = commitWhileBlocked(
            () -> organizationMapper.lockById(organization.getId()),
            "organization", organization.getId(), false,
            List.of(() -> leave(organization, leaving), () -> {
                orgMemberService.assertNotSoleOwnerOfAnyOrg(deleted.getId());
                orgMemberMapper.removeMember(organization.getId(), deleted.getId());
                return DONE;
            }));

        assertEquals(1, outcomes.stream().filter(DONE::equals).count(), outcomes.toString());
        assertEquals(1, ownerCount(organization));
    }

    /** Authority given up by leaving no longer backs a code issued before the owner left. */
    @Test
    void aCodeIssuedBeforeItsGrantorLeftCannotBeRedeemed() {
        Organization organization = newOrganization();
        User grantor = newUser();
        User coOwner = newUser();
        User member = newUser();
        orgMemberMapper.addMember(organization.getId(), grantor.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), coOwner.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), member.getId(), "admin");
        SoftwarePasskey passkey = SoftwarePasskeys.register(
            webAuthnService, userMapper, member, new EnrollmentEvidence(null, null), "Exit");
        SecurityContextHolder.clearContext();
        MfaAttestationCodeDto issued = steppedUpAs(grantor, () -> attestationService.issueForOrganization(
            organization.getId(), grantor.getId(), member.getId()));

        orgMemberService.leave(organization.getId(), grantor.getId());

        SoftwarePasskeys.authenticate(member);
        int epoch = userMapper.currentSessionEpoch(member.getId());
        assertEquals("authority_lost", assertThrows(PrivilegedMfaAttestationService.AttestationRefusal.class,
            () -> attestationService.redeem(member.getId(), epoch, passkey.rowId(), issued.code())).reason());
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

    private String leave(Organization organization, User member) {
        return asMember(member, () -> {
            orgMemberService.leave(organization.getId(), member.getId());
            return DONE;
        });
    }

    private <T> T steppedUpAs(User user, Supplier<T> action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionSecurityService.markStepUp(request, user.getId(), 0);
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
                AtomicLong connection = new AtomicLong();
                CountDownLatch started = new CountDownLatch(1);
                pending.add(executor.submit(() -> contend(connection, started, contender)));
                assertTrue(started.await(30, TimeUnit.SECONDS), "a contender never started");
                if (sharedWait) {
                    MySqlLockWaitProbe.awaitSharedRecordLock(
                        jdbcTemplate, connection.get(), table, Integer.toString(heldRowId));
                } else {
                    MySqlLockWaitProbe.awaitExclusiveRecordLock(
                        jdbcTemplate, connection.get(), table, Integer.toString(heldRowId));
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
     * Runs a contender in a {@code READ COMMITTED} transaction it owns, so the lock probe can name
     * its connection and it reads at the isolation the production paths use.
     */
    private String contend(AtomicLong connection, CountDownLatch started, Supplier<String> contender) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            return transaction.execute(status -> {
                connection.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                started.countDown();
                return contender.get();
            });
        } catch (RuntimeException refused) {
            return refused.getMessage();
        }
    }

    private int ownerCount(Organization organization) {
        Integer owners = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM org_member WHERE org_id = ? AND org_role = 'owner'",
            Integer.class, organization.getId());
        return owners == null ? 0 : owners;
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
}
