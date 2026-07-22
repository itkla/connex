package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Verifies every task position mutation serializes on the workspace board root in real MySQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskBoardConcurrencyIntegrationTest {

    @Autowired private TaskService taskService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private TaskMapper taskMapperSpy;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private ReferenceService referenceService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private Organization organization;
    private Workspace workspace;
    private User owner;
    private User secondOwner;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Task board " + unique);
        organization.setSlug("task-board-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Task board " + unique);
        workspace.setSlug("task-board-" + unique);
        workspaceMapper.insert(workspace);

        owner = user("task-board-owner-" + unique);
        secondOwner = user("task-board-second-" + unique);
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), secondOwner.getId(), "owner");
        when(referenceService.hydrateTasks(eq(workspace.getId()), anyList()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(referenceService.syncReferences(anyInt(), anyString(), anyInt(), anyString()))
            .thenReturn(List.of());
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM task WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM task_board_lock WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", owner.getId());
        }
        if (secondOwner != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", secondOwner.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void moveAndCreateSerializeAndKeepPositionsContiguous() throws Exception {
        Task moving = task("Moving", "todo", 0, owner);
        Task survivor = task("Survivor", "todo", 1, owner);
        Task existing = task("Existing", "in_progress", 0, owner);
        Task draft = new Task();
        draft.setDescription("Created while moving");
        draft.setAssignedTo(owner);

        assertSerializes(
            () -> inContext(owner, () -> taskService.move(moving.getId(), "in_progress", 0)),
            () -> inContext(owner, () -> taskService.create(draft))
        );

        assertColumn("todo", List.of(survivor.getId(), draft.getId()));
        assertColumn("in_progress", List.of(moving.getId(), existing.getId()));
        assertBoardInvariant();
    }

    @Test
    void moveAndCompleteSerializeAndRenumberBothColumns() throws Exception {
        Task moving = task("Moving", "todo", 0, owner);
        Task completing = task("Completing", "todo", 1, owner);
        Task survivor = task("Survivor", "todo", 2, owner);
        Task inProgress = task("In progress", "in_progress", 0, owner);
        Task done = task("Done", "done", 0, owner);

        assertSerializes(
            () -> inContext(owner, () -> taskService.move(moving.getId(), "in_progress", 0)),
            () -> inContext(owner, () -> taskService.complete(completing.getId()))
        );

        assertColumn("todo", List.of(survivor.getId()));
        assertColumn("in_progress", List.of(moving.getId(), inProgress.getId()));
        assertColumn("done", List.of(done.getId(), completing.getId()));
        assertBoardInvariant();
    }

    @Test
    void completionAndReopenUpdatesSerializeAcrossDistinctMemberships() throws Exception {
        Task completing = task("Completing", "todo", 0, owner);
        Task todoSurvivor = task("Todo survivor", "todo", 1, owner);
        Task reopening = task("Reopening", "done", 0, secondOwner);
        Task doneSurvivor = task("Done survivor", "done", 1, secondOwner);
        Task completionUpdate = updateDraft(completing, true);
        Task reopenUpdate = updateDraft(reopening, false);

        assertSerializes(
            () -> inContext(owner, () -> taskService.update(completing.getId(), completionUpdate)),
            () -> inContext(secondOwner, () -> taskService.update(reopening.getId(), reopenUpdate))
        );

        assertColumn("todo", List.of(todoSurvivor.getId(), reopening.getId()));
        assertColumn("done", List.of(doneSurvivor.getId(), completing.getId()));
        assertBoardInvariant();
    }

    private void assertSerializes(Callable<Task> firstOperation, Callable<Task> secondOperation)
            throws Exception {
        int workspaceId = workspace.getId();
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondLockEntered = new CountDownLatch(1);
        CountDownLatch secondLockAcquired = new CountDownLatch(1);
        AtomicInteger lockAttempts = new AtomicInteger();
        TaskMapper realTaskMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            int attempt = lockAttempts.incrementAndGet();
            if (attempt == 2) {
                secondLockEntered.countDown();
            }
            realTaskMapper.lockTaskBoard(workspaceId);
            if (attempt == 2) {
                secondLockAcquired.countDown();
            }
            if (attempt == 1) {
                firstLockAcquired.countDown();
                assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
            }
            return null;
        }).when(taskMapperSpy).lockTaskBoard(workspaceId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Task> first = executor.submit(firstOperation);
            assertTrue(firstLockAcquired.await(10, TimeUnit.SECONDS));
            Future<Task> second = executor.submit(secondOperation);
            assertTrue(secondLockEntered.await(10, TimeUnit.SECONDS));
            assertFalse(secondLockAcquired.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(1, TimeUnit.SECONDS));
            releaseFirst.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Task inContext(User actor, Callable<Task> operation) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        tenantContext.set(
            workspace.getId(), organization.getId(), actor.getId(), "owner", null);
        try {
            return operation.call();
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private void assertColumn(String status, List<Integer> expectedIds) {
        assertEquals(expectedIds, taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), status));
        List<Integer> positions = jdbcTemplate.queryForList(
            "SELECT position FROM task WHERE workspace_id = ? AND status = ? ORDER BY position, id",
            Integer.class,
            workspace.getId(),
            status
        );
        assertEquals(
            IntStream.range(0, expectedIds.size()).boxed().toList(),
            positions
        );
    }

    private void assertBoardInvariant() {
        Integer invalid = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM task WHERE workspace_id = ? AND ((status = 'done') <> completed)",
            Integer.class,
            workspace.getId()
        );
        assertEquals(0, invalid);
    }

    private Task task(String description, String status, int position, User assignee) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription(description + " " + UUID.randomUUID());
        task.setCompleted("done".equals(status));
        task.setStatus(status);
        task.setPosition(position);
        task.setAssignedTo(assignee);
        taskMapper.insert(task);
        return task;
    }

    private Task updateDraft(Task task, boolean completed) {
        Task update = new Task();
        update.setDescription(task.getDescription());
        update.setCompleted(completed);
        update.setDueDate(task.getDueDate());
        update.setAssignedTo(task.getAssignedTo());
        update.setPerson(task.getPerson());
        update.setDeal(task.getDeal());
        return update;
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("hash-" + username);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
