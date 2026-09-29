package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.UserDeletionTransaction;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountOffboardingService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.dto.WorkflowManualPreparationDto;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Exercises custom-role owner protection and recovery at HTTP and workspace-lock boundaries. */
@SpringBootTest
@TestPropertySource(properties = {
    "connex.workflows.runtime.enabled=true",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
class RoleRecoveryIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private TenantContext tenantContext;
    @Autowired private UserDeletionTransaction userDeletionTransaction;
    @MockitoSpyBean private WorkspaceMapper workspaceMapper;
    @MockitoBean private AuditService auditService;
    @MockitoBean private ProviderAccountOffboardingService providerAccountOffboardingService;

    private final List<User> members = new ArrayList<>();

    private MockMvc mockMvc;
    private Organization organization;
    private Workspace workspace;
    private User owner;

    @BeforeEach
    void setUp() {
        clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        String suffix = UUID.randomUUID().toString();
        organization = new Organization();
        organization.setName("Role recovery " + suffix);
        organization.setSlug("role-recovery-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(organization.getName());
        workspace.setSlug(organization.getSlug());
        workspaceMapper.insert(workspace);
        owner = newMember("owner");
    }

    /** Removes committed fixtures before another context's startup backfill can observe them. */
    @AfterEach
    void tearDown() {
        clearContext();
        if (workspace != null) {
            int workspaceId = workspace.getId();
            jdbcTemplate.update("DELETE FROM workflow_intervention WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workflow_step_attempt WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workflow_step_run WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                "DELETE FROM workflow_invocation_record WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workflow_run WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workflow_invocation WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                "DELETE FROM workflow_trigger_outbox WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                "UPDATE workflow SET runtime_owner = 'legacy', active_version_id = NULL"
                    + " WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM workflow_version WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workflow WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM rule WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workflow_runtime_workspace WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM task WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM task_board_lock WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM report_definition WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM notification WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (User member : members.reversed()) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", member.getId());
        }
        members.clear();
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
        workspace = null;
        organization = null;
        owner = null;
    }

    private void clearContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    @Test
    void delegateCannotAssignOrEditOwnersRole() throws Exception {
        WorkspaceRole ownerRole = role("Owner administration", "MEMBER_MANAGE", "ROLE_MANAGE");
        workspaceMapper.setMemberCustomRole(workspace.getId(), owner.getId(), ownerRole.getId());
        User delegate = newMember("member");
        WorkspaceRole roleManager = role("Role manager", "ROLE_MANAGE");
        workspaceMapper.setMemberCustomRole(workspace.getId(), delegate.getId(), roleManager.getId());
        WorkspaceRole empty = role("Empty");

        request(delegate, get(rolesPath())).andExpect(status().isOk());
        assign(delegate, owner, empty).andExpect(status().isForbidden());
        update(delegate, ownerRole).andExpect(status().isForbidden());
        request(delegate, delete(rolesPath() + "/" + ownerRole.getId())).andExpect(status().isForbidden());

        assertRecoveryPermissions(owner);
        assertEquals(ownerRole.getId(), workspaceMapper.getMemberRoleId(workspace.getId(), owner.getId()));
    }

    @Test
    void soleOwnerCannotAssignOverlayWithoutEitherRecoveryPermission() throws Exception {
        for (String permission : List.of("PERSON_CREATE", "MEMBER_MANAGE", "ROLE_MANAGE")) {
            int roleId = objectMapper.readTree(request(owner, post(rolesPath())
                    .content("{\"name\":\"Limited " + permission + "\",\"permissions\":[\"" + permission + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asInt();
            assertTrue(roleId > 0);
            request(owner, patch(memberPath(owner)).content("{\"roleId\":" + roleId + "}"))
                .andExpect(status().isBadRequest());
            assertRecoveryPermissions(owner);
        }
    }

    @Test
    void sharedRoleEditCannotRemoveLastRecoveryOwner() throws Exception {
        WorkspaceRole shared = role("Shared administration", "MEMBER_MANAGE", "ROLE_MANAGE");
        User secondOwner = newMember("owner");
        User member = newMember("member");
        for (User assigned : List.of(owner, secondOwner, member)) {
            workspaceMapper.setMemberCustomRole(workspace.getId(), assigned.getId(), shared.getId());
        }

        update(owner, shared).andExpect(status().isBadRequest());
        request(owner, delete(rolesPath() + "/" + shared.getId())).andExpect(status().isBadRequest());
        assertRecoveryPermissions(owner);
        assertRecoveryPermissions(secondOwner);
        assertRecoveryPermissions(member);
    }

    @Test
    void soleOwnerCannotEmptyAlreadyAssignedRole() throws Exception {
        WorkspaceRole custom = role("Owner recovery", "MEMBER_MANAGE", "ROLE_MANAGE");
        workspaceMapper.setMemberCustomRole(workspace.getId(), owner.getId(), custom.getId());

        update(owner, custom).andExpect(status().isBadRequest());

        assertRecoveryPermissions(owner);
    }

    @Test
    void roleEditCanNarrowOneOwnerWhileAnotherOwnerRetainsRecovery() throws Exception {
        WorkspaceRole custom = role("Custom owner", "MEMBER_MANAGE", "ROLE_MANAGE");
        User secondOwner = newMember("owner");
        assign(owner, secondOwner, custom).andExpect(status().isOk());
        restoreOwner(owner, secondOwner);
        assign(owner, secondOwner, custom).andExpect(status().isOk());

        update(owner, custom).andExpect(status().isOk());

        assertTrue(workspaceService.permissionsFor(workspace.getId(), secondOwner.getId()).isEmpty());
        assertRecoveryPermissions(owner);
        restoreOwner(owner, secondOwner);
    }

    @Test
    void roleEditRechecksOwnerAssignmentCommittedWhileWaitingForWorkspaceLock() throws Exception {
        newMember("owner");
        WorkspaceRole custom = role("Future owner", "MEMBER_MANAGE", "ROLE_MANAGE");
        User delegate = newMember("member");
        WorkspaceRole manager = role("Delegate", "ROLE_MANAGE");
        workspaceMapper.setMemberCustomRole(workspace.getId(), delegate.getId(), manager.getId());

        int result = contendAtWorkspaceLock(
            () -> update(delegate, custom).andReturn().getResponse().getStatus(),
            () -> assign(owner, owner, custom).andExpect(status().isOk()));

        assertEquals(403, result);
        assertRecoveryPermissions(owner);
    }

    @Test
    void concurrentOwnerOverlaysCannotRemoveBothRecoveryPaths() throws Exception {
        User secondOwner = newMember("owner");
        WorkspaceRole restricted = role("Contact creator", "PERSON_CREATE");

        int result = contendAtWorkspaceLock(
            () -> assign(secondOwner, secondOwner, restricted).andReturn().getResponse().getStatus(),
            () -> assign(owner, owner, restricted).andExpect(status().isOk()));

        assertEquals(400, result);
        assertEquals(Set.of(Permission.PERSON_CREATE),
            workspaceService.permissionsFor(workspace.getId(), owner.getId()));
        assertRecoveryPermissions(secondOwner);
    }

    @Test
    void roleDeletionRechecksAssignmentCommittedWhileWaitingForWorkspaceLock() throws Exception {
        WorkspaceRole custom = role("Future assignment", "PERSON_CREATE");
        User member = newMember("member");
        User delegate = newMember("member");
        WorkspaceRole manager = role("Delegate", "ROLE_MANAGE");
        workspaceMapper.setMemberCustomRole(workspace.getId(), delegate.getId(), manager.getId());

        int result = contendAtWorkspaceLock(
            () -> request(delegate, delete(rolesPath() + "/" + custom.getId()))
                .andReturn().getResponse().getStatus(),
            () -> assign(owner, member, custom).andExpect(status().isOk()));

        assertEquals(400, result);
        assertNotNull(roleMapper.findRole(workspace.getId(), custom.getId()));
        assertEquals(custom.getId(), workspaceMapper.getMemberRoleId(workspace.getId(), member.getId()));
    }

    @Test
    void roleMutationDoesNotWaitForDepartureHoldingAnAssignedMembership() throws Exception {
        WorkspaceRole custom = role("Pending assignment", "PERSON_CREATE");
        User pending = newMember("member");
        workspaceMapper.removeMember(workspace.getId(), pending.getId());
        workspaceMapper.addPendingMember(workspace.getId(), pending.getId(), "member");
        workspaceMapper.setMemberCustomRole(workspace.getId(), pending.getId(), custom.getId());
        CountDownLatch snapshotAttempted = new CountDownLatch(1);
        WorkspaceMapper realMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            snapshotAttempted.countDown();
            return realMapper.lockRoleAssignees(workspace.getId(), custom.getId());
        }).when(workspaceMapper).lockRoleAssignees(workspace.getId(), custom.getId());

        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                userMapper.lockById(pending.getId());
                workspaceMapper.lockAuthorizationMembership(workspace.getId(), pending.getId());
                var mutation = executor.submit(() -> {
                    try {
                        return update(owner, custom).andReturn().getResponse().getStatus();
                    } finally {
                        clearContext();
                    }
                });
                try {
                    assertTrue(snapshotAttempted.await(10, TimeUnit.SECONDS));
                    assertEquals(409, mutation.get(10, TimeUnit.SECONDS));
                    assertEquals(workspace.getId(), workspaceMapper.lockWorkspaceForShare(workspace.getId()));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        assertEquals(List.of("PERSON_CREATE"), roleMapper.findPermissions(workspace.getId(), custom.getId()));
    }

    @Test
    void soleOwnerMayTakeAFullCatalogOverlayButNotAManagementOnlyOne() throws Exception {
        assign(owner, owner, role("Management only", "MEMBER_MANAGE", "ROLE_MANAGE"))
            .andExpect(status().isBadRequest());
        assertBuiltInOwner(owner);

        WorkspaceRole full = role("Full owner", Permission.grantableNames().toArray(String[]::new));
        assign(owner, owner, full).andExpect(status().isOk());

        assertEquals(full.getId(), workspaceMapper.getMemberRoleId(workspace.getId(), owner.getId()));
        assertEquals(Permission.grantableSet(),
            workspaceService.permissionsFor(workspace.getId(), owner.getId()));
        restoreOwner(owner, owner);
    }

    @Test
    void legacySoleOwnerRoleEditIsRefusedOnlyWhenItNarrowsTheOwner() throws Exception {
        WorkspaceRole custom = role("Legacy owner", "MEMBER_MANAGE", "ROLE_MANAGE");
        workspaceMapper.setMemberCustomRole(workspace.getId(), owner.getId(), custom.getId());

        request(owner, put(rolesPath() + "/" + custom.getId())
            .content("{\"name\":\"Renamed\",\"permissions\":[\"MEMBER_MANAGE\",\"ROLE_MANAGE\"]}"))
            .andExpect(status().isOk());
        update(owner, custom).andExpect(status().isBadRequest());

        assertRecoveryPermissions(owner);
    }

    @Test
    void createRoleDoesNotConflictWithAnOwnersNotificationMarkAllRead() throws Exception {
        User secondOwner = newMember("owner");

        try (var executor = Executors.newSingleThreadExecutor()) {
            int status = new TransactionTemplate(transactionManager).execute(transaction -> {
                notificationMapper.lockRecipientMemberships(owner.getId());
                var pending = executor.submit(() -> {
                    try {
                        return request(secondOwner, post(rolesPath())
                            .content("{\"name\":\"Concurrent\",\"permissions\":[\"PERSON_CREATE\"]}"))
                            .andReturn().getResponse().getStatus();
                    } finally {
                        clearContext();
                    }
                });
                try {
                    return pending.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertEquals(200, status);
        }
    }

    @Test
    void ownerDemotionDoesNotConflictWithANonOwnersNotificationMembershipLock() throws Exception {
        User secondOwner = newMember("owner");
        User unrelated = newMember("member");
        CountDownLatch snapshotAttempted = new CountDownLatch(1);
        WorkspaceMapper realMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            snapshotAttempted.countDown();
            return realMapper.lockActiveOwnerMembers(workspace.getId());
        }).when(workspaceMapper).lockActiveOwnerMembers(workspace.getId());

        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                notificationMapper.lockRecipientMemberships(unrelated.getId());
                var pending = executor.submit(() -> {
                    try {
                        return request(owner, patch(memberPath(owner)).content("{\"role\":\"member\"}"))
                            .andReturn().getResponse().getStatus();
                    } finally {
                        clearContext();
                    }
                });
                try {
                    assertTrue(snapshotAttempted.await(10, TimeUnit.SECONDS),
                        "Demotion must reach the owner snapshot while the non-owner row is locked");
                    assertEquals(200, pending.get(30, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }

        assertEquals("member", workspaceMapper.getRole(workspace.getId(), owner.getId()));
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), unrelated.getId()));
        assertBuiltInOwner(secondOwner);
    }

    @Test
    void lastBuiltInOwnerCannotBeDemotedAfterOverlayingTheOtherOwner() throws Exception {
        overlayOtherOwner();

        request(owner, patch(memberPath(owner)).content("{\"role\":\"member\"}"))
            .andExpect(status().isBadRequest());

        assertBuiltInOwner(owner);
    }

    @Test
    void lastBuiltInOwnerCannotBeRemovedAfterOverlayingTheOtherOwner() throws Exception {
        overlayOtherOwner();

        request(owner, delete(memberPath(owner))).andExpect(status().isBadRequest());

        assertBuiltInOwner(owner);
    }

    @Test
    void lastBuiltInOwnerCannotLeaveAfterOverlayingTheOtherOwner() throws Exception {
        overlayOtherOwner();

        request(owner, post("/api/workspaces/" + workspace.getId() + "/leave"))
            .andExpect(status().isBadRequest());

        assertBuiltInOwner(owner);
    }

    @Test
    void lastBuiltInOwnerCannotDeleteAccountAfterOverlayingTheOtherOwner() throws Exception {
        overlayOtherOwner();

        request(owner, delete("/api/users/" + owner.getId())).andExpect(status().isBadRequest());

        assertBuiltInOwner(owner);
        assertNotNull(userMapper.getUserById(owner.getId()));
        assertFalse(userMapper.isAccountDeletionReserved(owner.getId()));
        verifyNoInteractions(providerAccountOffboardingService);
    }

    @Test
    void ownerDemotionAndRemovalSucceedWhenAnotherBuiltInOwnerRemains() throws Exception {
        User secondOwner = newMember("owner");
        request(owner, patch(memberPath(owner)).content("{\"role\":\"member\"}"))
            .andExpect(status().isOk());
        restoreOwner(secondOwner, owner);

        request(secondOwner, delete(memberPath(owner))).andExpect(status().isNoContent());

        assertNull(workspaceMapper.getMember(workspace.getId(), owner.getId()));
        assertBuiltInOwner(secondOwner);
    }

    @Test
    void ownerCanLeaveWhenAnotherBuiltInOwnerRemains() throws Exception {
        User secondOwner = newMember("owner");

        request(owner, post("/api/workspaces/" + workspace.getId() + "/leave"))
            .andExpect(status().isOk());

        assertNull(workspaceMapper.getMember(workspace.getId(), owner.getId()));
        assertBuiltInOwner(secondOwner);
    }

    @Test
    void reservedOwnerCannotSupportDemotionDepartureOrAnotherAccountDeletion() throws Exception {
        User secondOwner = newMember("owner");
        String reservation = UUID.randomUUID().toString();
        userDeletionTransaction.reserve(secondOwner.getId(), reservation);
        try {
            request(owner, patch(memberPath(owner)).content("{\"role\":\"member\"}"))
                .andExpect(status().isBadRequest());
            request(owner, delete(memberPath(owner))).andExpect(status().isBadRequest());
            request(owner, post("/api/workspaces/" + workspace.getId() + "/leave"))
                .andExpect(status().isBadRequest());
            request(owner, delete("/api/users/" + owner.getId())).andExpect(status().isBadRequest());
            assertBuiltInOwner(owner);
            verifyNoInteractions(providerAccountOffboardingService);
        } finally {
            userDeletionTransaction.release(secondOwner.getId(), reservation);
        }
    }

    @Test
    void overlayRechecksReservationCommittedWhileWaitingForWorkspaceLock() throws Exception {
        User secondOwner = newMember("owner");
        WorkspaceRole restricted = role("Contact creator", "PERSON_CREATE");
        String reservation = UUID.randomUUID().toString();
        try {
            int result = contendAtWorkspaceLock(
                () -> assign(secondOwner, secondOwner, restricted).andReturn().getResponse().getStatus(),
                () -> {
                    userDeletionTransaction.reserve(owner.getId(), reservation);
                    return null;
                });

            assertEquals(400, result);
            assertBuiltInOwner(secondOwner);
        } finally {
            userDeletionTransaction.release(owner.getId(), reservation);
        }
    }

    @Test
    void reservationRenewalFencesRecoveryMutationsUntilCommit() throws Exception {
        User secondOwner = newMember("owner");
        WorkspaceRole restricted = role("Contact creator", "PERSON_CREATE");
        String reservation = UUID.randomUUID().toString();
        userDeletionTransaction.reserve(owner.getId(), reservation);
        CountDownLatch lockAttempted = new CountDownLatch(1);
        CountDownLatch responseCompleted = new CountDownLatch(1);
        WorkspaceMapper realMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("renewal-contender")) {
                lockAttempted.countDown();
            }
            return realMapper.lockWorkspace(workspace.getId());
        }).when(workspaceMapper).lockWorkspace(workspace.getId());

        try (var executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "renewal-contender"))) {
            var future = new TransactionTemplate(transactionManager).execute(transaction -> {
                userDeletionTransaction.renew(owner.getId(), reservation);
                var pending = executor.submit(() -> {
                    try {
                        return assign(secondOwner, secondOwner, restricted).andReturn().getResponse().getStatus();
                    } finally {
                        responseCompleted.countDown();
                        clearContext();
                    }
                });
                try {
                    assertTrue(lockAttempted.await(10, TimeUnit.SECONDS));
                    assertFalse(responseCompleted.await(1, TimeUnit.SECONDS),
                        "Recovery mutation must wait for the renewal transaction to commit");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return pending;
            });
            assertNotNull(future);
            assertEquals(400, future.get(30, TimeUnit.SECONDS));
            assertBuiltInOwner(secondOwner);
        } finally {
            userDeletionTransaction.release(owner.getId(), reservation);
        }
    }

    @Test
    void reportDeletionRefusesAnAdministratorDemotedWhileItWaitsForTheMembershipLock() throws Exception {
        User administrator = newMember("admin");
        WorkspaceRole restricted = role("Report deletion only", "REPORT_DELETE");
        int reportId = newReportAuthoredByOwner();
        CountDownLatch lockAttempted = new CountDownLatch(1);
        CountDownLatch responseCompleted = new CountDownLatch(1);
        WorkspaceMapper realMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("report-deletion-contender")) {
                lockAttempted.countDown();
            }
            return realMapper.lockAuthorizationMembership(workspace.getId(), administrator.getId());
        }).when(workspaceMapper).lockAuthorizationMembership(workspace.getId(), administrator.getId());

        try (var executor = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "report-deletion-contender"))) {
            var pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                workspaceMapper.lockAuthorizationMembership(workspace.getId(), administrator.getId());
                workspaceMapper.setMemberCustomRole(
                    workspace.getId(), administrator.getId(), restricted.getId());
                var deletion = executor.submit(() -> {
                    try {
                        return request(administrator, delete("/api/reports/" + reportId))
                            .andReturn().getResponse().getStatus();
                    } finally {
                        responseCompleted.countDown();
                        clearContext();
                    }
                });
                try {
                    assertTrue(lockAttempted.await(10, TimeUnit.SECONDS),
                        "Deletion must reach the administrator membership lock");
                    assertFalse(responseCompleted.await(1, TimeUnit.SECONDS),
                        "The administrator snapshot must wait for the demotion to commit");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return deletion;
            });
            assertNotNull(pending);
            assertEquals(403, pending.get(30, TimeUnit.SECONDS));
        }

        assertEquals(1, countReport(reportId));
        assertEquals(restricted.getId(),
            workspaceMapper.getMemberRoleId(workspace.getId(), administrator.getId()));
    }

    @Test
    void manualSystemDispatchRefusesRequesterDemotedWhileWaitingForMembershipLock() throws Exception {
        User secondOwner = newMember("owner");
        WorkspaceRole restricted = role("Workflow task manager", "RULE_MANAGE", "TASK_CREATE");
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(owner.getId());
        person.setName("Manual workflow subject");
        personMapper.insert(person);
        int workflowId = newSystemTaskWorkflow();
        String manualPath = "/api/workflows/" + workflowId + "/manual-runs";
        WorkflowManualPreparationDto prepared = objectMapper.readValue(
            request(owner, post(manualPath + "/prepare").content(
                "{\"sourceSurface\":\"record\",\"scope\":{\"kind\":\"single_record\",\"recordId\":"
                    + person.getId() + "}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
            WorkflowManualPreparationDto.class);
        assertTrue(prepared.confirmable());
        String confirmation = "{\"scopeToken\":\"" + prepared.scopeToken()
            + "\",\"scopeHash\":\"" + prepared.scopeHash() + "\"}";
        String idempotencyKey = UUID.randomUUID().toString();
        CountDownLatch lockAttempted = new CountDownLatch(1);
        CountDownLatch responseCompleted = new CountDownLatch(1);
        WorkspaceMapper realMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("manual-dispatch-contender")) {
                lockAttempted.countDown();
            }
            return realMapper.lockAuthorizationMembership(workspace.getId(), owner.getId());
        }).when(workspaceMapper).lockAuthorizationMembership(workspace.getId(), owner.getId());

        try (var executor = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "manual-dispatch-contender"))) {
            var pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                workspaceMapper.lockAuthorizationMembership(workspace.getId(), owner.getId());
                workspaceMapper.setMemberCustomRole(workspace.getId(), owner.getId(), restricted.getId());
                var dispatch = executor.submit(() -> {
                    try {
                        return request(owner, post(manualPath).header("Idempotency-Key", idempotencyKey)
                            .content(confirmation)).andReturn().getResponse().getStatus();
                    } finally {
                        responseCompleted.countDown();
                        clearContext();
                    }
                });
                try {
                    assertTrue(lockAttempted.await(10, TimeUnit.SECONDS),
                        "Dispatch must reach the requester's exact membership lock");
                    assertFalse(responseCompleted.await(1, TimeUnit.SECONDS),
                        "Dispatch must wait for the overlay demotion to commit");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return dispatch;
            });
            assertNotNull(pending);
            assertEquals(403, pending.get(30, TimeUnit.SECONDS));
        }

        assertEquals(Set.of(Permission.RULE_MANAGE, Permission.TASK_CREATE),
            workspaceService.permissionsFor(workspace.getId(), owner.getId()));
        assertEquals(0, countWorkflowRuns(workflowId));
        restoreOwner(secondOwner, owner);
        request(owner, post(manualPath).header("Idempotency-Key", idempotencyKey).content(confirmation))
            .andExpect(status().isOk());
        assertEquals(1, countWorkflowRuns(workflowId));
    }

    /** Publishes through the canonical deployment gate without creating a legacy projection. */
    private int newSystemTaskWorkflow() throws Exception {
        String workflowJson = """
            {"name":"Manual system task","recordType":"person","executionMode":"system",
             "definition":{"schemaVersion":1,"entryNodeId":"trigger","nodes":[
               {"id":"trigger","type":"TRIGGER","config":{"type":"entity_change","events":["person.updated"]}},
               {"id":"action","type":"ACTION","config":{"type":"create_task","title":"Follow up"}},
               {"id":"end","type":"END"}],"edges":[
               {"id":"trigger-action","sourceNodeId":"trigger","targetNodeId":"action","outcome":"next"},
               {"id":"action-end","sourceNodeId":"action","targetNodeId":"end","outcome":"next"}]},
             "canvas":{"positions":{"trigger":{"x":0,"y":0},"action":{"x":300,"y":0},
               "end":{"x":600,"y":0}},"viewport":{"x":0,"y":0,"zoom":1}}}
            """;
        int workflowId = objectMapper.readTree(request(owner, post("/api/workflows").content(workflowJson))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asInt();
        assertTrue(workflowId > 0);
        assertEquals("canonical", objectMapper.readTree(
            request(owner, post("/api/workflows/" + workflowId + "/publish")
                .content("{\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("runtimeOwner").asString());
        assertNull(jdbcTemplate.queryForObject(
            "SELECT legacy_rule_id FROM workflow WHERE workspace_id = ? AND id = ?",
            Integer.class, workspace.getId(), workflowId));
        request(owner, post("/api/workflows/" + workflowId + "/enable")).andExpect(status().isOk());
        return workflowId;
    }

    private int countWorkflowRuns(int workflowId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_run WHERE workspace_id = ? AND workflow_id = ?",
            Integer.class, workspace.getId(), workflowId), "workflow run count");
    }

    private int newReportAuthoredByOwner() {
        String name = "Owner authored " + UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO report_definition (workspace_id, name, cadence, config_json, created_by) "
                + "VALUES (?, ?, 'custom', '{\"widgets\": []}', ?)",
            workspace.getId(), name, owner.getId());
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT id FROM report_definition WHERE workspace_id = ? AND name = ?",
            Integer.class, workspace.getId(), name), "report id");
    }

    private int countReport(int reportId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM report_definition WHERE workspace_id = ? AND id = ?",
            Integer.class, workspace.getId(), reportId), "report count");
    }

    private void overlayOtherOwner() throws Exception {
        User other = newMember("owner");
        assign(owner, other, role("Contact creator", "PERSON_CREATE")).andExpect(status().isOk());
    }

    private void restoreOwner(User actor, User target) throws Exception {
        request(actor, patch(memberPath(target)).content("{\"role\":\"owner\"}"))
            .andExpect(status().isOk());
        assertBuiltInOwner(target);
    }

    private void assertBuiltInOwner(User member) {
        assertEquals("owner", workspaceMapper.getRole(workspace.getId(), member.getId()));
        assertNull(workspaceMapper.getMemberRoleId(workspace.getId(), member.getId()));
        assertEquals(Permission.grantableSet(), workspaceService.permissionsFor(workspace.getId(), member.getId()));
    }

    private int contendAtWorkspaceLock(Callable<Integer> contender, Callable<?> winner) throws Exception {
        CountDownLatch lockAttempted = new CountDownLatch(1);
        WorkspaceMapper realMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("role-recovery-contender")) {
                lockAttempted.countDown();
            }
            return realMapper.lockWorkspace(workspace.getId());
        }).when(workspaceMapper).lockWorkspace(workspace.getId());
        try (var executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "role-recovery-contender"))) {
            var future = new TransactionTemplate(transactionManager).execute(transaction -> {
                userMapper.lockById(owner.getId());
                workspaceMapper.lockWorkspace(workspace.getId());
                var pending = executor.submit(() -> {
                    try {
                        return contender.call();
                    } finally {
                        clearContext();
                    }
                });
                try {
                    assertTrue(lockAttempted.await(10, TimeUnit.SECONDS), "Contender must reach the workspace lock");
                    winner.call();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return pending;
            });
            assertNotNull(future);
            return future.get(30, TimeUnit.SECONDS);
        }
    }

    private void assertRecoveryPermissions(User member) {
        assertTrue(workspaceService.permissionsFor(workspace.getId(), member.getId())
            .containsAll(Set.of(Permission.MEMBER_MANAGE, Permission.ROLE_MANAGE)));
    }

    private ResultActions assign(User actor, User target, WorkspaceRole role) throws Exception {
        return request(actor, patch(memberPath(target)).content("{\"roleId\":" + role.getId() + "}"));
    }

    private ResultActions update(User actor, WorkspaceRole role) throws Exception {
        return request(actor, put(rolesPath() + "/" + role.getId())
            .content("{\"name\":\"Narrowed\",\"permissions\":[]}"));
    }

    private ResultActions request(User actor, MockHttpServletRequestBuilder builder) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, userMapper.currentSessionEpoch(actor.getId()));
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, actor.getId());
        PublicApiTestSecuritySupport.stepUp(session, actor.getId());
        return mockMvc.perform(builder.header("X-Workspace-Id", workspace.getId())
            .contentType(MediaType.APPLICATION_JSON).session(session).with(user(actor)).with(csrf().asHeader()));
    }

    private String rolesPath() {
        return "/api/workspaces/" + workspace.getId() + "/roles";
    }

    private String memberPath(User member) {
        return "/api/workspaces/" + workspace.getId() + "/members/" + member.getId();
    }

    private WorkspaceRole role(String name, String... permissions) {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName(name);
        roleMapper.insertRole(role);
        if (permissions.length > 0) {
            roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of(permissions));
        }
        return role;
    }

    private User newMember(String builtInRole) {
        String suffix = UUID.randomUUID().toString();
        User member = new User();
        member.setUsername("recovery_" + suffix);
        member.setDisplayName("Recovery " + suffix);
        member.setEmail(suffix + "@example.com");
        member.setPasswordHash("unused");
        member.setTimezone("UTC");
        userMapper.insert(member);
        members.add(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), builtInRole);
        PublicApiTestSecuritySupport.enrollPasskey(jdbcTemplate, member);
        return member;
    }
}
