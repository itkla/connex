package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.AuditIntegrityMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.publicapi.ApiCredential;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Pins final erasure's membership fence on committed fixtures and distinct physical connections. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountDeletionMembershipTailConcurrencyIntegrationTest extends AbstractServiceTest {
    private static final String RESERVATION = "membership-tail-erasure";
    @MockitoSpyBean private WorkspaceMapper roots;
    @MockitoSpyBean private UserMapper users;
    @MockitoSpyBean private OrganizationMapper organizations;
    @MockitoSpyBean private OrgMemberMapper orgMembers;
    @MockitoSpyBean private ApiCredentialMapper credentials;
    @MockitoSpyBean private AuditIntegrityMapper heads;
    @Autowired private UserDeletionTransaction deletion;
    @Autowired private PersonService people;
    @Autowired private UserAccountCatalogOffboardingService recovery;
    @Autowired private AuditIntegrityService integrity;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> accounts = new ArrayList<>();
    private final List<Integer> scopes = new ArrayList<>();
    private int orgId;
    private int workspaceId;

    @Override
    protected User newUser() {
        User user = super.newUser();
        accounts.add(user.getId());
        return user;
    }

    @BeforeEach
    void isolatedRoots() {
        Organization org = new Organization();
        org.setName("Erasure tail " + unique());
        org.setSlug("erasure-tail-" + unique());
        organizations.insert(org);
        orgId = org.getId();
        orgMembers.addMember(orgId, currentUser.getId(), "owner");
        workspace = newWorkspace();
        workspaceId = workspace.getId();
        roots.addMember(workspaceId, currentUser.getId(), "owner");
        authenticateAs(currentUser, workspaceId);
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setOrgId(orgId);
        created.setName("Erasure tail workspace");
        created.setSlug("erasure-tail-" + unique());
        roots.insert(created);
        scopes.add(created.getId());
        return created;
    }

    @AfterEach
    void cleanup() {
        reset(roots, users, organizations, orgMembers, credentials, heads);
        for (int id : scopes) {
            jdbc.update("DELETE FROM person WHERE workspace_id = ?", id);
            jdbc.update("DELETE FROM workspace WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", orgId);
        accounts.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        accounts.clear();
        scopes.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"organization-owner", "credential-creator", "credential-revoker"})
    void assignmentHoldingNonOwnerMembershipCompletesBeforeDeletionReachesItsTail(String variant) throws Exception {
        User erased = newUser();
        List<Integer> erasedWorkspaceIds = new ArrayList<>(List.of(workspaceId));
        if ("organization-owner".equals(variant)) {
            orgMembers.addMember(orgId, erased.getId(), "owner");
            Workspace owned = newWorkspace();
            roots.addMember(owned.getId(), currentUser.getId(), "owner");
            roots.addMember(owned.getId(), erased.getId(), "owner");
            erasedWorkspaceIds.add(owned.getId());
        } else {
            orgMembers.addMember(orgId, erased.getId(), "admin");
        }
        ApiCredential reference = credential("credential-revoker".equals(variant) ? currentUser : erased);
        if ("credential-revoker".equals(variant)) {
            jdbc.update("UPDATE api_credential SET revoked_by_id = ?, revoked_at = UTC_TIMESTAMP(6) WHERE id = ?",
                erased.getId(), reference.getId());
        }
        Person person = newPerson(null);
        int passkey = approveForStepUp(currentUser);
        assertEquals(1, users.reserveAccountDeletion(erased.getId(), RESERVATION));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch atMembership = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        AtomicLong deletionConnection = new AtomicLong();
        AtomicInteger tailCalls = observeTail(deletionConnection);
        WorkspaceMapper real = sql.getMapper(WorkspaceMapper.class);
        doAnswer(call -> {
            Integer locked = real.lockActiveMembership(workspaceId, erased.getId());
            writerConnection.set(connection());
            held.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(roots).lockActiveMembership(workspaceId, erased.getId());
        doAnswer(call -> {
            deletionConnection.set(connection());
            atMembership.countDown();
            return real.lockAccountMembershipForUpdate(workspaceId, erased.getId());
        }).when(roots).lockAccountMembershipForUpdate(workspaceId, erased.getId());
        assertCascadeRetains(erased, erasedWorkspaceIds, List.of(orgId));

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> writer = workers.submit(() -> {
                authenticateAs(currentUser, workspaceId);
                installProof(currentUser, passkey);
                try {
                    people.updateOwner(person.getId(), erased.getId());
                } finally {
                    clearAuthentication();
                }
            });
            try {
                assertTrue(held.await(30, TimeUnit.SECONDS));
                Future<?> deleter = workers.submit(() -> deleteAs(erased));
                assertTrue(atMembership.await(30, TimeUnit.SECONDS));
                assertNotEquals(writerConnection.get(), deletionConnection.get());
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, deletionConnection.get(), "workspace_member",
                    workspaceId + ", " + erased.getId());
                assertEquals(0, tailCalls.get(), "No organization, credential or head may precede the membership wait");
                assertNotNull(users.getUserById(erased.getId()));
                assertNotNull(credentials.findByIdWithScopes(workspaceId, reference.getId()));
                assertNull(jdbc.queryForObject("SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
                assertTrue(audits().isEmpty());
                release.countDown();
                completed(writer);
                completed(deleter);
            } finally {
                release.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
        assertErased(erased);
        assertNull(credentials.findByIdWithScopes(workspaceId, reference.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_credential_scope WHERE credential_id = ?",
            Integer.class, reference.getId()));
        assertEquals(erased.getId(), jdbc.queryForObject("SELECT owner_id FROM person WHERE id = ?",
            Integer.class, person.getId()), "The committed assignment must reach post-delete recovery");
        recovery.sweepAssignmentsAfterDeletion(erased.getId());
        assertNull(jdbc.queryForObject("SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
        List<AuditLog> committed = audits();
        assertEquals(List.of("person.updateOwner", "api_credential.account_erased", "user.delete"),
            committed.stream().map(AuditLog::getAction).toList());
        assertChain(committed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "suspended", "inactive-scope"})
    void erasureRetainsMembershipsOutsideActiveAuthorization(String state) throws Exception {
        User erased = newUser();
        Workspace extra = newWorkspace();
        roots.addMember(extra.getId(), erased.getId(), "member");
        if ("inactive-scope".equals(state)) {
            jdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", extra.getId());
            jdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", orgId);
        } else {
            jdbc.update("UPDATE workspace_member SET status = ? WHERE workspace_id = ? AND user_id = ?",
                state, extra.getId(), erased.getId());
        }
        orgMembers.addMember(orgId, erased.getId(), "admin");
        assertEquals(1, users.reserveAccountDeletion(erased.getId(), RESERVATION));
        CountDownLatch atMembership = new CountDownLatch(1);
        AtomicLong deletionConnection = new AtomicLong();
        AtomicInteger tailCalls = observeTail(deletionConnection);
        WorkspaceMapper real = sql.getMapper(WorkspaceMapper.class);
        doAnswer(call -> {
            deletionConnection.set(connection());
            atMembership.countDown();
            return real.lockAccountMembershipForUpdate(extra.getId(), erased.getId());
        }).when(roots).lockAccountMembershipForUpdate(extra.getId(), erased.getId());
        assertCascadeRetains(erased, List.of(workspaceId, extra.getId()), List.of(orgId));
        try (var worker = Executors.newSingleThreadExecutor()) {
            Future<?>[] deleter = new Future<?>[1];
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertNotNull(real.lockAuthorizationMembership(extra.getId(), erased.getId()));
                deleter[0] = worker.submit(() -> deleteAs(erased));
                await(atMembership);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, deletionConnection.get(), "workspace_member",
                    extra.getId() + ", " + erased.getId());
                assertEquals(0, tailCalls.get());
                assertNotNull(users.getUserById(erased.getId()));
                assertTrue(audits().isEmpty());
            });
            completed(deleter[0]);
        }
        assertErased(erased);
        assertChain(audits());
    }

    /** The foreign-key parent wait must precede discovery of an inserted, uncommitted membership. */
    @Test
    void uncommittedMembershipInsertIsWaitedOnAndIncluded() throws Exception {
        User erased = newUser();
        Workspace extra = newWorkspace();
        assertEquals(1, users.reserveAccountDeletion(erased.getId(), RESERVATION));
        AtomicLong deletionConnection = new AtomicLong();
        CountDownLatch atAccount = new CountDownLatch(1);
        UserMapper realUsers = sql.getMapper(UserMapper.class);
        doAnswer(call -> {
            deletionConnection.set(connection());
            atAccount.countDown();
            return realUsers.lockById(erased.getId());
        }).when(users).lockById(erased.getId());
        assertCascadeRetains(erased, List.of(workspaceId, extra.getId()), List.of(orgId));
        try (var worker = Executors.newSingleThreadExecutor()) {
            Future<?>[] deleter = new Future<?>[1];
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                roots.addPendingMember(extra.getId(), erased.getId(), "member");
                orgMembers.addMember(orgId, erased.getId(), "admin");
                deleter[0] = worker.submit(() -> deleteAs(erased));
                await(atAccount);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, deletionConnection.get(), "app_user",
                    Integer.toString(erased.getId()));
                assertNotNull(users.getUserById(erased.getId()));
                assertTrue(audits().isEmpty());
            });
            completed(deleter[0]);
        }
        assertErased(erased);
        assertChain(audits());
    }

    /** A completeness scan must not fence another account's insert while erasure waits for O X. */
    @Test
    void neighboringInviteInsertCommitsWhileDeletionHasCompletedMembershipScans() throws Exception {
        User erased = newUser();
        User invitee = newUser();
        roots.removeMember(workspaceId, invitee.getId());
        Workspace invitationWorkspace = newWorkspace();
        orgMembers.addMember(orgId, erased.getId(), "owner");
        assertEquals(1, users.reserveAccountDeletion(erased.getId(), RESERVATION));
        CountDownLatch beforeOrganization = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OrganizationMapper real = sql.getMapper(OrganizationMapper.class);
        doAnswer(call -> {
            assertEquals("READ-COMMITTED", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            beforeOrganization.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return real.lockById(orgId);
        }).when(organizations).lockById(orgId);
        assertCascadeRetains(erased, List.of(workspaceId), List.of(orgId));
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> deleter = workers.submit(() -> deleteAs(erased));
            try {
                assertTrue(beforeOrganization.await(30, TimeUnit.SECONDS));
                Future<?> insertion = workers.submit(() -> {
                    authenticateAs(invitee, invitationWorkspace.getId());
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(status -> {
                            assertEquals(invitee.getId(), users.lockById(invitee.getId()));
                            assertEquals(invitationWorkspace.getId(), roots.lockWorkspace(invitationWorkspace.getId()));
                            assertEquals(orgId, organizations.lockByIdForShare(orgId));
                            roots.addPendingMember(invitationWorkspace.getId(), invitee.getId(), "member");
                            orgMembers.addMember(orgId, invitee.getId(), "admin");
                        });
                    } finally {
                        clearAuthentication();
                    }
                });
                completed(insertion);
                assertNotNull(roots.getMember(invitationWorkspace.getId(), invitee.getId()));
                release.countDown();
                completed(deleter);
            } finally {
                release.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
        assertErased(erased);
        assertNotNull(roots.getMember(invitationWorkspace.getId(), invitee.getId()));
        assertChain(audits());
    }

    private AtomicInteger observeTail(AtomicLong deletionConnection) {
        AtomicInteger calls = new AtomicInteger();
        OrganizationMapper realOrganizations = sql.getMapper(OrganizationMapper.class);
        ApiCredentialMapper realCredentials = sql.getMapper(ApiCredentialMapper.class);
        AuditIntegrityMapper realHeads = sql.getMapper(AuditIntegrityMapper.class);
        doAnswer(call -> {
            if (connection() == deletionConnection.get()) calls.incrementAndGet();
            return realOrganizations.lockById(call.getArgument(0));
        }).when(organizations).lockById(anyInt());
        doAnswer(call -> {
            if (connection() == deletionConnection.get()) calls.incrementAndGet();
            return realOrganizations.lockByIdForShare(call.getArgument(0));
        }).when(organizations).lockByIdForShare(anyInt());
        doAnswer(call -> {
            if (connection() == deletionConnection.get()) calls.incrementAndGet();
            return realCredentials.findByIdForUpdate(call.getArgument(0), call.getArgument(1));
        }).when(credentials).findByIdForUpdate(anyInt(), anyLong());
        doAnswer(call -> {
            if (connection() == deletionConnection.get()) calls.incrementAndGet();
            return realHeads.ensureHead(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        }).when(heads).ensureHead(anyString(), anyInt(), anyString());
        return calls;
    }

    private void assertCascadeRetains(User erased, List<Integer> workspaceIds, List<Integer> organizationIds) {
        UserMapper real = sql.getMapper(UserMapper.class);
        doAnswer(call -> {
            assertEquals("READ-COMMITTED", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            for (int id : workspaceIds) assertRetained("workspace_member", id + ", " + erased.getId());
            for (int id : organizationIds) assertRetained("org_member", id + ", " + erased.getId());
            return real.delete(erased.getId());
        }).when(users).delete(erased.getId());
    }

    private void assertRetained(String table, String primaryKey) {
        assertTrue(jdbc.queryForObject("""
            SELECT COUNT(*) FROM performance_schema.data_locks l
            JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID
            WHERE t.PROCESSLIST_ID = CONNECTION_ID() AND l.OBJECT_SCHEMA = DATABASE()
              AND l.OBJECT_NAME = ? AND l.INDEX_NAME = 'PRIMARY' AND l.LOCK_TYPE = 'RECORD'
              AND l.LOCK_MODE LIKE 'X%' AND l.LOCK_STATUS = 'GRANTED' AND l.LOCK_DATA = ?
            """, Integer.class, table, primaryKey) > 0, "Cascade must reuse retained " + table + " row " + primaryKey);
    }

    private void deleteAs(User erased) {
        authenticateAs(erased, workspaceId);
        try {
            deletion.delete(erased.getId(), RESERVATION);
        } finally {
            clearAuthentication();
        }
    }

    private ApiCredential credential(User creator) {
        ApiCredential created = new ApiCredential();
        created.setWorkspaceId(workspaceId);
        created.setOrganizationId(orgId);
        created.setCreatedById(creator.getId());
        created.setMembershipId(Objects.requireNonNull(jdbc.queryForObject(
            "SELECT membership_id FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
            Long.class, workspaceId, creator.getId())));
        created.setName("Erasure tail evidence");
        created.setTokenHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        created.setTokenLast4("1582");
        created.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        credentials.insert(created);
        credentials.insertScopes(created.getId(), List.of("crm.read"));
        return created;
    }

    private void installProof(User user, int passkey) {
        var attributes = (org.springframework.web.context.request.ServletRequestAttributes)
            org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        var session = attributes.getRequest().getSession();
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
            user.getId() + ":" + session.getAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR) + ":" + passkey);
    }

    private long connection() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }

    private void assertErased(User erased) {
        assertNull(users.getUserById(erased.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workspace_member WHERE user_id = ?", Integer.class, erased.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM org_member WHERE user_id = ?", Integer.class, erased.getId()));
        assertNotNull(roots.getMember(workspaceId, currentUser.getId()));
        assertEquals("owner", orgMembers.lockExactRole(orgId, currentUser.getId()));
    }

    private List<AuditLog> audits() {
        return jdbc.query("""
            SELECT audit_log.*, untrusted_client_asserted_correlation_id AS untrusted_client_asserted_correlation_hmac
            FROM audit_log WHERE integrity_workspace_id = ? ORDER BY chain_index
            """, BeanPropertyRowMapper.newInstance(AuditLog.class), workspaceId);
    }

    private void assertChain(List<AuditLog> entries) {
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().allMatch(integrity::hasValidIntegrity));
        for (int i = 1; i < entries.size(); i++) {
            assertEquals(entries.get(i - 1).getRowHash(), entries.get(i).getPrevHash());
            assertEquals(entries.get(i - 1).getChainIndex() + 1, entries.get(i).getChainIndex());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void completed(Future<?> result) throws Exception {
        try {
            result.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlFailure) {
                    assertNotEquals(1213, sqlFailure.getErrorCode());
                    assertNotEquals(1205, sqlFailure.getErrorCode());
                }
            }
            throw failure;
        }
    }
}
