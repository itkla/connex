package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ReportScheduleRequest;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Real mapper sessions must discard the pre-lock privilege answer after promotion (#1897). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PrivilegedGateConcurrencyIntegrationTest {
    @Autowired private ScheduleService scheduleService;
    @Autowired private ReportService reportService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private UserMapper userMapper;
    @MockitoSpyBean private WorkspaceService workspaceService;
    @MockitoSpyBean private PrivilegedAccountService privilegedAccountService;
    @MockitoBean private AuthService authService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private Organization organization;
    private Workspace workspace;
    private Workspace otherWorkspace;
    private User user;
    private ReportDefinition report;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Gate race " + suffix);
        organization.setSlug("gate-race-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Gate race " + suffix);
        workspace.setSlug("gate-race-" + suffix);
        workspaceMapper.insert(workspace);
        user = new User();
        user.setUsername("gate_race_" + suffix);
        user.setDisplayName("Gate Race " + suffix);
        user.setEmail(suffix + "@gate-race.example.com");
        user.setPasswordHash(passwordEncoder.encode("Gate-Race-Credential-2026!"));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        report = new ReportDefinition();
        report.setWorkspaceId(workspace.getId());
        report.setName("Gate race report");
        report.setCadence("weekly");
        report.setConfigJson("{}");
        report.setCreatedBy(user.getId());
        reportMapper.insertDefinition(report);
        when(authService.getCurrentUser()).thenReturn(user);
        doReturn(workspace.getId()).when(workspaceService).getCurrentWorkspaceId();
        doReturn(user.getId()).when(workspaceService).getCurrentUserId();
        doNothing().when(workspaceService).requirePermission(any(Permission.class));
        doThrow(new RecentAuthenticationRequiredException())
                .when(sessionSecurityService).requireRecentAuthentication(user.getId());
        assertFalse(userMapper.isPrivilegedAccount(user.getId()));
    }

    @AfterEach
    void cleanUp() {
        if (otherWorkspace != null) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", otherWorkspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", otherWorkspace.getId());
        }
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM report_definition WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (user != null) {
            userMapper.delete(user.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete", "reportDelete"})
    void committedPromotionAfterPreCheckIsRefusedAndAuditedUnderTheLock(String operation) throws Exception {
        ReportSchedule original = prepareSchedule(operation);
        AtomicBoolean deferredInsideTheGate = new AtomicBoolean();
        Answer<Void> captureTransaction = invocation -> {
            deferredInsideTheGate.set(TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive());
            return null;
        };
        doAnswer(captureTransaction).when(auditService).deferExportStepUpRefusal();
        doAnswer(captureTransaction).when(auditService).deferScheduleDeleteStepUpRefusal();
        CountDownLatch preCheckRead = new CountDownLatch(1);
        CountDownLatch promotionCommitted = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        doAnswer(invocation -> {
            boolean privileged = realUserMapper.isPrivilegedAccount(user.getId());
            if (firstRead.compareAndSet(true, false)) {
                assertFalse(privileged);
                preCheckRead.countDown();
                if (!promotionCommitted.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Privilege promotion did not commit");
                }
            }
            return privileged;
        }).when(privilegedAccountService).isPrivileged(user.getId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RecentAuthenticationRequiredException> mutation = executor.submit(() -> assertThrows(
                    RecentAuthenticationRequiredException.class, () -> invoke(operation)));
            assertTrue(preCheckRead.await(10, TimeUnit.SECONDS));
            promote();
            promotionCommitted.countDown();
            mutation.get(20, TimeUnit.SECONDS);
        } finally {
            promotionCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        verify(privilegedAccountService, times(2)).isPrivileged(user.getId());
        verify(userMapper).lockAssignedCustomRoleRowsForShare(user.getId());
        verifyRefusalAuditedOnce(operation);
        assertTrue(deferredInsideTheGate.get(),
                "the under-lock refusal must be deferred from inside the gate's transaction, where its"
                        + " completion callback can run after the account row is released");
        assertUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete", "reportDelete"})
    void alreadyPrivilegedAccountIsRefusedAndAuditedBeforeTheRoot(String operation) {
        ReportSchedule original = prepareSchedule(operation);
        promote();

        assertThrows(RecentAuthenticationRequiredException.class, () -> invoke(operation));

        if ("create".equals(operation) || "update".equals(operation)) {
            verify(auditService).recordExportStepUpRefused();
            verify(auditService, never()).recordScheduleDeleteStepUpRefused();
        } else {
            verify(auditService).recordScheduleDeleteStepUpRefused();
            verify(auditService, never()).recordExportStepUpRefused();
        }
        verify(auditService, never()).deferExportStepUpRefusal();
        verify(auditService, never()).deferScheduleDeleteStepUpRefusal();
        verify(userMapper, never()).lockByIdForShare(user.getId());
        verify(userMapper, never()).lockAssignedCustomRoleRowsForShare(user.getId());
        assertUnchanged(original);
    }

    /**
     * A promotion still in flight is waited for, not just a committed one observed. Privilege is
     * account-wide, so the promotion here makes the account an administrator of another workspace,
     * holding the grantee's account row and that membership for update. The schedule mutation in this
     * workspace passes its unlocked check, then waits behind the promotion under its locks, and once the
     * promotion commits the re-check refuses it without an audit.
     */
    @Test
    void anInFlightPromotionInAnotherWorkspaceIsWaitedForAndRefusesTheMutation() throws Exception {
        ReportSchedule original = prepareSchedule("delete");
        otherWorkspace = new Workspace();
        otherWorkspace.setOrgId(organization.getId());
        otherWorkspace.setName(workspace.getName() + " other");
        otherWorkspace.setSlug(workspace.getSlug() + "-other");
        workspaceMapper.insert(otherWorkspace);
        workspaceMapper.addMember(otherWorkspace.getId(), user.getId(), "member");
        CountDownLatch promotionHeld = new CountDownLatch(1);
        CountDownLatch commitPromotion = new CountDownLatch(1);
        AtomicReference<Long> promotionConnection = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> promotion = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        promotionConnection.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        assertNotNull(userMapper.lockById(user.getId()));
                        assertNotNull(workspaceMapper.lockWorkspace(otherWorkspace.getId()));
                        assertNotNull(workspaceMapper.lockAuthorizationMembership(otherWorkspace.getId(), user.getId()));
                        assertEquals(1, jdbcTemplate.update(
                                "UPDATE workspace_member SET role = 'admin' WHERE workspace_id = ? AND user_id = ?",
                                otherWorkspace.getId(), user.getId()));
                        promotionHeld.countDown();
                        try {
                            assertTrue(commitPromotion.await(30, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }));
            if (!promotionHeld.await(10, TimeUnit.SECONDS)) {
                promotion.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The promotion never held the account");
            }
            Future<RecentAuthenticationRequiredException> mutation = executor.submit(() -> assertThrows(
                    RecentAuthenticationRequiredException.class, () -> invoke("delete")));
            awaitBlockedBy(promotionConnection.get(), mutation);
            commitPromotion.countDown();
            promotion.get(20, TimeUnit.SECONDS);
            mutation.get(20, TimeUnit.SECONDS);
        } finally {
            commitPromotion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        verifyRefusalAuditedOnce("delete");
        assertUnchanged(original);
    }

    /**
     * A schedule committed while a privileged account's report deletion waits on the definition locks
     * is observed under them. The cascade would now destroy a gated schedule, so the deletion needs a
     * fresh step-up: it is refused, audited, and nothing is deleted. At REPEATABLE READ the deletion's
     * snapshot predated the schedule, so the cascade removed it without any step-up.
     */
    @Test
    void aScheduleCommittedWhileAPrivilegedReportDeletionWaitsIsObservedUnderTheLock() throws Exception {
        promote();
        CountDownLatch scheduleHeld = new CountDownLatch(1);
        CountDownLatch commitSchedule = new CountDownLatch(1);
        AtomicReference<Long> scheduleConnection = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> scheduling = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        scheduleConnection.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        assertNotNull(prepareSchedule("delete"));
                        scheduleHeld.countDown();
                        try {
                            assertTrue(commitSchedule.await(30, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }));
            if (!scheduleHeld.await(10, TimeUnit.SECONDS)) {
                scheduling.get(0, TimeUnit.SECONDS);
                throw new AssertionError("The schedule insert never held its parent definition");
            }
            Future<RecentAuthenticationRequiredException> deletion = executor.submit(() -> assertThrows(
                    RecentAuthenticationRequiredException.class, () -> invoke("reportDelete")));
            awaitBlockedBy(scheduleConnection.get(), deletion);
            commitSchedule.countDown();
            scheduling.get(20, TimeUnit.SECONDS);
            deletion.get(20, TimeUnit.SECONDS);
        } finally {
            commitSchedule.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        verifyRefusalAuditedOnce("reportDelete");
        assertNotNull(reportMapper.getDefinition(workspace.getId(), report.getId()));
        assertNotNull(scheduleMapper.getByReport(workspace.getId(), report.getId()));
    }

    /**
     * Two report deletions by the same account in two workspaces, each holding its own membership
     * exclusively, must not deadlock. The re-check share-locks only the account's assigned custom-role
     * rows, never its memberships, so neither deletion waits on the other's membership; share-locking
     * every membership, as {@code lockAssignedCustomRoleIds} does, deadlocks them.
     */
    @Test
    void sameAccountReportDeletionsInTwoWorkspacesDoNotDeadlock() throws Exception {
        otherWorkspace = new Workspace();
        otherWorkspace.setOrgId(organization.getId());
        otherWorkspace.setName(workspace.getName() + " other");
        otherWorkspace.setSlug(workspace.getSlug() + "-other");
        workspaceMapper.insert(otherWorkspace);
        workspaceMapper.addMember(otherWorkspace.getId(), user.getId(), "member");
        assignCustomRole(workspace.getId());
        assignCustomRole(otherWorkspace.getId());
        assertFalse(userMapper.isPrivilegedAccount(user.getId()));
        ReportDefinition otherReport = new ReportDefinition();
        otherReport.setWorkspaceId(otherWorkspace.getId());
        otherReport.setName("Gate race report");
        otherReport.setCadence("weekly");
        otherReport.setConfigJson("{}");
        otherReport.setCreatedBy(user.getId());
        reportMapper.insertDefinition(otherReport);
        ThreadLocal<Integer> currentWorkspace = new ThreadLocal<>();
        doAnswer(invocation -> currentWorkspace.get() == null ? workspace.getId() : currentWorkspace.get())
                .when(workspaceService).getCurrentWorkspaceId();
        CountDownLatch bothHoldMemberships = new CountDownLatch(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            bothHoldMemberships.countDown();
            assertTrue(bothHoldMemberships.await(10, TimeUnit.SECONDS));
            return result;
        }).when(workspaceService).isLockedBuiltInAdministrator(anyInt(), eq(user.getId()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> here = executor.submit(() -> {
                currentWorkspace.set(workspace.getId());
                reportService.delete(report.getId());
            });
            Future<?> there = executor.submit(() -> {
                currentWorkspace.set(otherWorkspace.getId());
                reportService.delete(otherReport.getId());
            });
            here.get(30, TimeUnit.SECONDS);
            there.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(reportMapper.getDefinition(workspace.getId(), report.getId()));
        assertNull(reportMapper.getDefinition(otherWorkspace.getId(), otherReport.getId()));
    }

    private void assignCustomRole(int workspaceId) {
        String roleName = "Gate race role " + workspaceId;
        jdbcTemplate.update("INSERT INTO workspace_role (workspace_id, name) VALUES (?, ?)", workspaceId, roleName);
        Integer roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace_role WHERE workspace_id = ? AND name = ?", Integer.class, workspaceId, roleName);
        assertEquals(1, jdbcTemplate.update(
                "UPDATE workspace_member SET role_id = ? WHERE workspace_id = ? AND user_id = ?",
                roleId, workspaceId, user.getId()));
    }

    /**
     * Waits until a lock request in this schema is queued behind the transaction on
     * {@code blockingConnection}, and fails at once with the waiter's outcome if it finishes first.
     */
    private void awaitBlockedBy(long blockingConnection, Future<?> waiter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                try {
                    waiter.get();
                    throw new AssertionError("The mutation completed without waiting behind the promotion");
                } catch (ExecutionException failure) {
                    throw new AssertionError("The mutation failed before waiting behind the promotion",
                            failure.getCause());
                }
            }
            Integer waits = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads blocking
                      ON blocking.THREAD_ID = waits.BLOCKING_THREAD_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE() AND blocking.PROCESSLIST_ID = ?
                    """, Integer.class, blockingConnection);
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The mutation never waited behind the promotion");
    }

    /**
     * The under-lock refusal is recorded exactly once, with the summary that matches what was
     * attempted, and deferred until completion rather than appended while the account row is held
     * (#1986).
     */
    private void verifyRefusalAuditedOnce(String operation) {
        if ("create".equals(operation) || "update".equals(operation)) {
            verify(auditService).deferExportStepUpRefusal();
            verify(auditService, never()).deferScheduleDeleteStepUpRefusal();
        } else {
            verify(auditService).deferScheduleDeleteStepUpRefusal();
            verify(auditService, never()).deferExportStepUpRefusal();
        }
        verify(auditService, never()).recordExportStepUpRefused();
        verify(auditService, never()).recordScheduleDeleteStepUpRefused();
    }

    /** Uses the grantee account, workspace and membership order of built-in role mutation. */
    private void promote() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertNotNull(userMapper.lockById(user.getId()));
            assertNotNull(workspaceMapper.lockWorkspace(workspace.getId()));
            assertNotNull(workspaceMapper.lockAuthorizationMembership(workspace.getId(), user.getId()));
            assertEquals(1, jdbcTemplate.update(
                    "UPDATE workspace_member SET role = 'admin' WHERE workspace_id = ? AND user_id = ?",
                    workspace.getId(), user.getId()));
        });
        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
    }

    private ReportSchedule prepareSchedule(String operation) {
        if ("create".equals(operation)) {
            return null;
        }
        ReportSchedule schedule = new ReportSchedule();
        schedule.setWorkspaceId(workspace.getId());
        schedule.setReportDefinitionId(report.getId());
        schedule.setCadence("weekly");
        schedule.setRecipientUserIds("[" + user.getId() + "]");
        schedule.setTimezone("UTC");
        schedule.setHourOfDay(9);
        schedule.setEnabled(true);
        schedule.setRunAsUserId(user.getId());
        schedule.setCreatedBy(user.getId());
        schedule.setNextRunAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        scheduleMapper.insert(schedule);
        return scheduleMapper.getByReport(workspace.getId(), report.getId());
    }

    private void invoke(String operation) {
        ReportScheduleRequest request = new ReportScheduleRequest(
                "monthly", List.of(user.getId()), "UTC", 10, false);
        switch (operation) {
            case "create" -> scheduleService.create(report.getId(), request);
            case "update" -> scheduleService.update(report.getId(), request);
            case "delete" -> scheduleService.delete(report.getId());
            case "reportDelete" -> reportService.delete(report.getId());
            default -> throw new IllegalArgumentException("Unknown gate: " + operation);
        }
    }

    private void assertUnchanged(ReportSchedule original) {
        assertNotNull(reportMapper.getDefinition(workspace.getId(), report.getId()));
        ReportSchedule current = scheduleMapper.getByReport(workspace.getId(), report.getId());
        if (original == null) {
            assertNull(current);
        } else {
            assertEquals(original, current);
        }
    }
}
