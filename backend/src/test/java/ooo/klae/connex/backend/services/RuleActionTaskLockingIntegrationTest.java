package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.support.TestEntityFixtures;

/** Reproduces an audited workflow versus a direct task create on two physical connections. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RuleActionTaskLockingIntegrationTest {
    @Autowired private RuleActionExecutor actions;
    @Autowired private RuleActionIsolation isolation;
    @Autowired private WorkflowActionRetryPolicy retries;
    @Autowired private AutomationExecutor automation;
    @Autowired private TaskService tasks;
    @Autowired private WorkspaceService authority;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private CompanyMapper companies;
    @Autowired private NotificationMapper notifications;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private Workspace workspace;
    private User actor;
    private User recipient;
    private Company company;
    private final String suffix = UUID.randomUUID().toString();
    private final String noteContent = "Workflow audit head " + suffix;
    private final String workflowTask = "Workflow task " + suffix;
    private final String directTask = "Direct task " + suffix;

    @BeforeEach
    void fixtures() {
        workspace = workspaces.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Test Workspace");
            workspace.setSlug("default");
            workspaces.insert(workspace);
            TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, workspace.getId());
        }
        actor = TestEntityFixtures.newUser(users, workspaces, workspace);
        recipient = TestEntityFixtures.newUser(users, workspaces, workspace);
        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Workflow locking source " + suffix);
        automation.runAs(workspace.getId(), actor, "member", () -> companies.insert(company));
    }

    @AfterEach
    void cleanup() {
        notifications.deleteAllForRecipient(workspace.getId(), recipient.getId());
        jdbc.update("DELETE FROM task WHERE workspace_id = ? AND description IN (?, ?)",
            workspace.getId(), workflowTask, directTask);
        jdbc.update("DELETE FROM note WHERE workspace_id = ? AND content = ?", workspace.getId(), noteContent);
        jdbc.update("DELETE FROM company WHERE workspace_id = ? AND id = ?", workspace.getId(), company.getId());
        workspaces.removeMember(workspace.getId(), recipient.getId());
        workspaces.removeMember(workspace.getId(), actor.getId());
        users.delete(recipient.getId());
        users.delete(actor.getId());
    }

    @Test
    void taskRecipientContentionRefusesBeforeADeadlockAndAFreshAttemptSucceeds() throws Exception {
        CountDownLatch headHeld = new CountDownLatch(1);
        CountDownLatch directStarted = new CountDownLatch(1);
        AtomicLong directConnection = new AtomicLong();
        RuleFireContext context = new RuleFireContext(workspace.getId(), 1, "company", company.getId(),
            actor.getId(), suffix);
        RuleAction note = new RuleAction();
        note.setType("create_note");
        note.setBody(noteContent);
        RuleAction task = new RuleAction();
        task.setType("create_task");
        task.setTitle(workflowTask);
        task.setTargetUserId(recipient.getId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var workflow = workers.submit(() -> assertThrows(RuleActionRetryRequiredException.class,
                () -> automation.runAs(workspace.getId(), actor, "member", () -> {
                    transaction().executeWithoutResult(status -> {
                        authority.lockedMemberPermissionsFor(workspace.getId(), actor.getId());
                        isolation.run(() -> actions.execute(note, context));
                        headHeld.countDown();
                        await(directStarted);
                        assertNotEquals(connectionId(), directConnection.get());
                        MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, directConnection.get(),
                            "audit_log_integrity_head", "'workspace', " + workspace.getId());
                        isolation.run(() -> actions.execute(task, context));
                    });
                    return null;
                })));
            try {
                assertTrue(headHeld.await(15, TimeUnit.SECONDS), "workflow must retain its note's audit head");
                var direct = workers.submit(() -> automation.runAs(workspace.getId(), recipient, "member",
                    () -> transaction().execute(status -> {
                        directConnection.set(connectionId());
                        directStarted.countDown();
                        Task request = new Task();
                        request.setDescription(directTask);
                        request.setAssignedTo(recipient);
                        return tasks.create(request);
                    })));

                RuleActionRetryRequiredException refusal = workflow.get(20, TimeUnit.SECONDS);
                CannotAcquireLockException lock = assertInstanceOf(CannotAcquireLockException.class, refusal.getCause());
                assertInstanceOf(ConflictException.class, lock.getCause());
                assertTrue(retries.transientDatabaseFailure(refusal));
                Task committed = direct.get(20, TimeUnit.SECONDS);
                assertNotNull(committed);
                assertNotNull(committed.getAssignedTo());
                assertEquals(recipient.getId(), committed.getAssignedTo().getId());
                assertEquals(1, taskCount(directTask));
                assertEquals(0, taskCount(workflowTask));
                assertEquals(0, noteCount());
            } finally {
                directStarted.countDown();
            }
        }

        automation.runAs(workspace.getId(), actor, "member", () -> {
            transaction().executeWithoutResult(status -> {
                authority.lockedMemberPermissionsFor(workspace.getId(), actor.getId());
                isolation.run(() -> actions.execute(note, context));
                isolation.run(() -> actions.execute(task, context));
            });
            return null;
        });
        assertEquals(1, taskCount(directTask));
        assertEquals(1, taskCount(workflowTask));
        assertEquals(1, noteCount());
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
        return transaction;
    }

    private long connectionId() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }

    private int taskCount(String description) {
        return Objects.requireNonNull(jdbc.queryForObject(
            "SELECT COUNT(*) FROM task WHERE workspace_id = ? AND description = ?", Integer.class,
            workspace.getId(), description));
    }

    private int noteCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
            "SELECT COUNT(*) FROM note WHERE workspace_id = ? AND content = ?", Integer.class,
            workspace.getId(), noteContent));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
