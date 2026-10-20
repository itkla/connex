package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.publicapi.ApiCredential;
import ooo.klae.connex.backend.publicapi.ApiCredentialLifecycleService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Real teardown/departure waits pin workspace-before-organization ordering. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserOffboardingRootLockConcurrencyIntegrationTest extends AbstractServiceTest {
    @MockitoSpyBean private TenantLifecycleControlMapper lifecycleMapper;
    @MockitoSpyBean private WorkspaceMapper roots;
    @MockitoSpyBean private OrganizationMapper organizationMapper;
    @MockitoSpyBean private ApiCredentialMapper credentials;
    @MockitoSpyBean private ApiCredentialLifecycleService credentialLifecycle;
    @Autowired private AuditIntegrityService integrity;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceService workspaces;
    @Autowired private UserOffboardingService offboarding;
    @Autowired private TenantLifecycleControlOperations lifecycle;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> accounts = new ArrayList<>();
    private int organizationId;
    private int workspaceId;

    @Override
    protected User newUser() {
        User user = super.newUser();
        accounts.add(user.getId());
        return user;
    }

    @BeforeEach
    void isolatedLifecycleRoots() {
        Organization org = new Organization();
        org.setName("Departure lock " + unique());
        org.setSlug("departure-lock-" + unique());
        organizationMapper.insert(org);
        organizationId = org.getId();
        orgMemberMapper.addMember(organizationId, currentUser.getId(), "owner");
        Workspace isolated = new Workspace();
        isolated.setOrgId(organizationId);
        isolated.setName("Departure lock workspace");
        isolated.setSlug("departure-lock-" + unique());
        roots.insert(isolated);
        workspaceId = isolated.getId();
        roots.addMember(workspaceId, currentUser.getId(), "owner");
        workspace = isolated;
    }

    @AfterEach
    void removeCommittedFixtures() {
        reset(lifecycleMapper, roots, organizationMapper, credentials, credentialLifecycle);
        jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM tenant_operation_lease WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        jdbc.update("DELETE FROM organization WHERE id = ?", organizationId);
        accounts.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        accounts.clear();
    }

    @Test
    void memberRemovalReusesRetainedRootsWhileCredentialCleanupHasAnOrganizationWriterQueued() throws Exception {
        User target = newUser();
        User rootWriter = newUser();
        roots.updateMemberRole(workspaceId, rootWriter.getId(), "owner");
        orgMemberMapper.addMember(organizationId, rootWriter.getId(), "owner");
        ApiCredential credential = new ApiCredential();
        credential.setWorkspaceId(workspaceId);
        credential.setOrganizationId(organizationId);
        credential.setCreatedById(target.getId());
        credential.setMembershipId(Objects.requireNonNull(jdbc.queryForObject(
            "SELECT membership_id FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
            Long.class, workspaceId, target.getId())));
        credential.setName("Removal root retention");
        credential.setTokenHash(UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""));
        credential.setTokenLast4("1582");
        credential.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        credentials.insert(credential);
        credentials.insertScopes(credential.getId(), List.of("crm.read"));
        authenticateAs(currentUser, workspaceId);
        Person person = newPerson(null);
        jdbc.update("UPDATE person SET owner_id = ? WHERE id = ?", target.getId(), person.getId());
        int approvedPasskey = approveForStepUp(currentUser);
        CompletableFuture<Void> atCredentials = new CompletableFuture<>();
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch writerAtOrganization = new CountDownLatch(1);
        AtomicLong removalConnection = new AtomicLong();
        AtomicLong writerConnection = new AtomicLong();
        AtomicInteger workspaceCleanupRoots = new AtomicInteger();
        AtomicInteger organizationShareRoots = new AtomicInteger();
        AtomicInteger workspaceRootsBeforeCredentials = new AtomicInteger();
        AtomicInteger organizationRootsBeforeCredentials = new AtomicInteger();
        WorkspaceMapper realRoots = sql.getMapper(WorkspaceMapper.class);
        OrganizationMapper realOrganizations = sql.getMapper(OrganizationMapper.class);
        ApiCredentialMapper realCredentials = sql.getMapper(ApiCredentialMapper.class);
        doAnswer(call -> {
            workspaceCleanupRoots.incrementAndGet();
            return realRoots.lockWorkspaceOrgIdForShare(workspaceId);
        }).when(roots).lockWorkspaceOrgIdForShare(workspaceId);
        doAnswer(call -> {
            organizationShareRoots.incrementAndGet();
            return realOrganizations.lockByIdForShare(organizationId);
        }).when(organizationMapper).lockByIdForShare(organizationId);
        doAnswer(call -> {
            assertEquals(1, workspaceCleanupRoots.get());
            assertTrue(organizationShareRoots.get() > 0);
            workspaceRootsBeforeCredentials.set(workspaceCleanupRoots.get());
            organizationRootsBeforeCredentials.set(organizationShareRoots.get());
            return call.callRealMethod();
        }).when(credentialLifecycle).deleteForMembership(workspaceId, target.getId());
        doAnswer(call -> {
            var locked = realCredentials.listByMembershipForUpdate(workspaceId, target.getId());
            assertEquals(List.of(credential.getId()), locked.stream().map(ApiCredential::getId).toList());
            assertEquals(workspaceRootsBeforeCredentials.get(), workspaceCleanupRoots.get(),
                "Credential cleanup must reuse the workspace root retained by departure cleanup");
            assertEquals(organizationRootsBeforeCredentials.get(), organizationShareRoots.get(),
                "Credential cleanup must reuse the organization root retained by departure cleanup");
            removalConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            atCredentials.complete(null);
            assertTrue(releaseCleanup.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(credentials).listByMembershipForUpdate(workspaceId, target.getId());
        doAnswer(call -> {
            writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            writerAtOrganization.countDown();
            return realOrganizations.lockById(organizationId);
        }).when(organizationMapper).lockById(organizationId);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> removal = workers.submit(() -> {
                try {
                    authenticateAs(currentUser, workspaceId);
                    var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                    var session = attributes.getRequest().getSession();
                    session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
                        currentUser.getId() + ":" + session.getAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR)
                            + ":" + approvedPasskey);
                    workspaces.removeMember(workspaceId, currentUser.getId(), target.getId());
                    if (!atCredentials.isDone()) {
                        throw new AssertionError("Removal completed without reaching exact credential cleanup");
                    }
                } catch (RuntimeException | Error failure) {
                    atCredentials.completeExceptionally(failure);
                    throw failure;
                } finally {
                    clearAuthentication();
                }
            });
            try {
                atCredentials.get(30, TimeUnit.SECONDS);
                Future<?> writer = workers.submit(() -> {
                    authenticateAs(rootWriter, workspaceId);
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                            assertEquals(organizationId, organizationMapper.lockById(organizationId)));
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(writerAtOrganization.await(30, TimeUnit.SECONDS));
                assertNotEquals(removalConnection.get(), writerConnection.get());
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, writerConnection.get(),
                    "organization", Integer.toString(organizationId));
                assertNotNull(roots.getMember(workspaceId, target.getId()));
                assertNotNull(credentials.findByIdWithScopes(workspaceId, credential.getId()));
                assertEquals(target.getId(), jdbc.queryForObject(
                    "SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
                assertTrue(committedAudits().isEmpty());
                releaseCleanup.countDown();
                completedWithoutLockFailure(removal);
                completedWithoutLockFailure(writer);
            } finally {
                releaseCleanup.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, workspaceCleanupRoots.get());
        assertNull(roots.getMember(workspaceId, target.getId()));
        assertNull(credentials.findByIdWithScopes(workspaceId, credential.getId()));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM api_credential_scope WHERE credential_id = ?", Integer.class, credential.getId()));
        assertNull(jdbc.queryForObject("SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
        List<AuditLog> audits = committedAudits();
        assertEquals(List.of("api_credential.membership_removed", "workspace.member.remove"),
            audits.stream().map(AuditLog::getAction).toList());
        assertTrue(audits.stream().allMatch(integrity::hasValidIntegrity));
        assertTrue(audits.stream().allMatch(entry -> currentUser.getId() == entry.getIntegrityActorId()));
        assertEquals(audits.getFirst().getRowHash(), audits.getLast().getPrevHash());
        assertEquals(audits.getFirst().getChainIndex() + 1, audits.getLast().getChainIndex());
    }

    private List<AuditLog> committedAudits() {
        return jdbc.query("""
            SELECT audit_log.*,
                   untrusted_client_asserted_correlation_id AS untrusted_client_asserted_correlation_hmac
            FROM audit_log WHERE integrity_workspace_id = ? ORDER BY chain_index
            """, BeanPropertyRowMapper.newInstance(AuditLog.class), workspaceId);
    }

    private static void completedWithoutLockFailure(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
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

    @ParameterizedTest
    @ValueSource(strings = {"leave", "fresh"})
    void teardownHoldingWorkspaceCompletesBeforeDepartureRequestsOrganization(String path) throws Exception {
        User target = newUser();
        if ("fresh".equals(path)) {
            roots.removeMember(workspaceId, target.getId());
        }
        CountDownLatch workspaceHeld = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch requestingWorkspace = new CountDownLatch(1);
        AtomicLong departureConnection = new AtomicLong();
        TenantLifecycleControlMapper realLifecycle = sql.getMapper(TenantLifecycleControlMapper.class);
        WorkspaceMapper realRoots = sql.getMapper(WorkspaceMapper.class);
        doAnswer(call -> {
            workspaceHeld.countDown();
            assertTrue(releaseTeardown.await(30, TimeUnit.SECONDS));
            return realLifecycle.lockOrganization(call.getArgument(0));
        }).when(lifecycleMapper).lockOrganization(organizationId);
        doAnswer(call -> {
            departureConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            requestingWorkspace.countDown();
            return realRoots.lockWorkspaceOrgIdForShare(call.getArgument(0));
        }).when(roots).lockWorkspaceOrgIdForShare(anyInt());

        try (var workers = Executors.newFixedThreadPool(2)) {
            var teardown = workers.submit(() -> lifecycle.acquireWorkspaceTeardown(
                organizationId, workspaceId, currentUser.getId()));
            try {
                assertTrue(workspaceHeld.await(30, TimeUnit.SECONDS));
                var departure = workers.submit(() -> {
                    authenticateAs(target, workspaceId);
                    try {
                        if ("leave".equals(path)) {
                            workspaces.leaveWorkspace(workspaceId, target.getId());
                        } else {
                            TransactionTemplate tx = new TransactionTemplate(transactionManager);
                            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                            tx.executeWithoutResult(status -> offboarding.prepareFreshMembership(workspaceId, target.getId()));
                        }
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(requestingWorkspace.await(30, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, departureConnection.get(),
                    "workspace", Integer.toString(workspaceId));
                releaseTeardown.countDown();
                assertNotNull(teardown.get(10, TimeUnit.SECONDS));
                departure.get(10, TimeUnit.SECONDS);
                assertNull(roots.getMember(workspaceId, target.getId()));
            } finally {
                releaseTeardown.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
    }
}
