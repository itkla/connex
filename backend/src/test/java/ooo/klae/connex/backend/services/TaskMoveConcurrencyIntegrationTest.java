package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

/** Verifies board moves serialize sibling position rewrites in real MySQL transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskMoveConcurrencyIntegrationTest {

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
    private User currentUser;
    private Task firstTask;
    private Task secondTask;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Task move " + unique);
        organization.setSlug("task-move-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Task move " + unique);
        workspace.setSlug("task-move-" + unique);
        workspaceMapper.insert(workspace);

        currentUser = new User();
        currentUser.setUsername("task-move-" + unique);
        currentUser.setDisplayName("Task move " + unique);
        currentUser.setEmail("task-move-" + unique + "@example.com");
        currentUser.setPasswordHash("hash-" + unique);
        currentUser.setTimezone("UTC");
        userMapper.insert(currentUser);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");

        firstTask = task("First " + unique, 0);
        secondTask = task("Second " + unique, 1);
        taskMapper.insert(firstTask);
        taskMapper.insert(secondTask);
        when(referenceService.hydrateTasks(eq(workspace.getId()), anyList()))
            .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM task WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void concurrentMovesOfDifferentTasksSerializeWithoutDeadlock() throws Exception {
        int workspaceId = workspace.getId();
        int orgId = organization.getId();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger lockReads = new AtomicInteger();
        TaskMapper realTaskMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            int lockRead = lockReads.incrementAndGet();
            if (lockRead == 3) secondStarted.countDown();
            int taskId = invocation.getArgument(1);
            Task locked = realTaskMapper.getTaskByIdForUpdate(workspaceId, taskId);
            if (lockRead == 2) {
                firstLocked.countDown();
                assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
            }
            return locked;
        }).when(taskMapperSpy).getTaskByIdForUpdate(eq(workspaceId), anyInt());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Task> firstMove = executor.submit(
                () -> moveTask(firstTask.getId(), 1, workspaceId, orgId));
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            Future<Task> secondMove = executor.submit(
                () -> moveTask(secondTask.getId(), 0, workspaceId, orgId));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            releaseFirst.countDown();

            firstMove.get(20, TimeUnit.SECONDS);
            secondMove.get(20, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(
            List.of(secondTask.getId(), firstTask.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspaceId, "todo")
        );
    }

    private Task moveTask(int taskId, int position, int workspaceId, int orgId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        tenantContext.set(workspaceId, orgId, currentUser.getId(), "owner", null);
        try {
            return taskService.move(taskId, "todo", position);
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private Task task(String description, int position) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription(description);
        task.setCompleted(false);
        task.setStatus("todo");
        task.setPosition(position);
        task.setAssignedTo(currentUser);
        return task;
    }
}
