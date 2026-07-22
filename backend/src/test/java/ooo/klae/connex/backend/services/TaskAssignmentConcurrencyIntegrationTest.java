package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Verifies task assignment cannot outlive a concurrently removed membership. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskAssignmentConcurrencyIntegrationTest {

    @Autowired private TaskService taskService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private WorkspaceMapper workspaceMapper;
    @MockitoSpyBean private NotificationMapper notificationMapper;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private NotificationStateVersionService notificationStateVersionService;
    @MockitoBean private ReferenceService referenceService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private Organization organization;
    private Workspace workspace;
    private User currentUser;
    private User targetMember;
    private Task task;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Task assignment " + unique);
        organization.setSlug("task-assignment-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Task assignment " + unique);
        workspace.setSlug("task-assignment-" + unique);
        workspaceMapper.insert(workspace);

        currentUser = user("task-assignment-owner-" + unique);
        targetMember = user("task-assignment-target-" + unique);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), targetMember.getId(), "member");

        task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Keep original assignee " + unique);
        task.setCompleted(false);
        task.setStatus("todo");
        task.setAssignedTo(currentUser);
        taskMapper.insert(task);
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
        if (targetMember != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", targetMember.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void committedMemberRemovalMakesWaitingReassignmentFailClosed() throws Exception {
        int workspaceId = workspace.getId();
        int targetUserId = targetMember.getId();
        CountDownLatch removalLocked = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        CountDownLatch assignmentStarted = new CountDownLatch(1);
        NotificationMapper realNotificationMapper = sqlSessionTemplate.getMapper(NotificationMapper.class);
        WorkspaceMapper realWorkspaceMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            List<Integer> locked = realNotificationMapper.lockRecipientMemberships(targetUserId);
            removalLocked.countDown();
            assertTrue(releaseRemoval.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(notificationMapper).lockRecipientMemberships(targetUserId);
        doAnswer(invocation -> {
            assignmentStarted.countDown();
            return realWorkspaceMapper.lockActiveMembership(workspaceId, targetUserId);
        }).when(workspaceMapper).lockActiveMembership(eq(workspaceId), eq(targetUserId));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> removal = executor.submit(() -> removeTargetMember(workspaceId));
            assertTrue(removalLocked.await(10, TimeUnit.SECONDS));
            Future<Task> assignment = executor.submit(() -> reassignTask(workspaceId));
            assertTrue(assignmentStarted.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> assignment.get(1, TimeUnit.SECONDS)
            );
            releaseRemoval.countDown();

            removal.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> assignment.get(20, TimeUnit.SECONDS)
            );
            assertTrue(hasCause(failure, ForbiddenException.class));
        } finally {
            releaseRemoval.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Task persisted = taskMapper.getTaskById(workspaceId, task.getId());
        assertEquals(currentUser.getId(), persisted.getAssignedTo().getId());
        assertFalse(persisted.isCompleted());
        assertNull(workspaceMapper.getMember(workspaceId, targetUserId));
        verifyNoInteractions(referenceService, ruleTriggers, notificationChanges);
    }

    private void removeTargetMember(int workspaceId) {
        authenticate(workspaceId);
        try {
            workspaceService.removeMember(workspaceId, currentUser.getId(), targetMember.getId());
        } finally {
            clearAuthentication();
        }
    }

    private Task reassignTask(int workspaceId) {
        authenticate(workspaceId);
        Task update = new Task();
        update.setDescription("Rejected reassignment");
        update.setCompleted(false);
        update.setStatus("todo");
        update.setAssignedTo(targetMember);
        try {
            return taskService.update(task.getId(), update);
        } finally {
            clearAuthentication();
        }
    }

    private void authenticate(int workspaceId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        tenantContext.set(
            workspaceId,
            organization.getId(),
            currentUser.getId(),
            "owner",
            null
        );
    }

    private void clearAuthentication() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
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

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
