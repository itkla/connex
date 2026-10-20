package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.mappers.UserMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionPrincipalServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private UserMapper userMapper;
    @Mock private SystemActor systemActor;

    private WorkflowExecutionPrincipalService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionPrincipalService(
            workspaceService, userMapper, systemActor, new RuleDefinitionCodec(new tools.jackson.databind.ObjectMapper()));
    }

    @Test
    void removedUserActorFailsClosedWithVisibleCode() {
        WorkflowVersion version = version("user", 17, 19);
        when(workspaceService.getRole(7, 17)).thenReturn(null);

        WorkflowExecutionException failure = assertThrows(
            WorkflowExecutionException.class,
            () -> service.resolve(7, version));

        assertEquals("actor_unavailable", failure.code());
        assertEquals(true, failure.interventionRequired());
    }

    @Test
    void userAndSystemModesResolveTheirCurrentNarrowPrincipals() {
        WorkflowVersion userVersion = version("user", 17, 19);
        User user = user(17);
        when(workspaceService.getRole(7, 17)).thenReturn("member");
        when(userMapper.getUserById(17)).thenReturn(user);
        WorkflowExecutionPrincipal resolvedUser = service.resolve(7, userVersion);
        assertEquals(17, resolvedUser.actorUserId());
        assertEquals(17, resolvedUser.attributionUserId());

        WorkflowVersion systemVersion = version("system", null, 19);
        User system = user(1);
        when(workspaceService.getRole(7, 19)).thenReturn("admin");
        when(systemActor.user()).thenReturn(system);
        WorkflowExecutionPrincipal resolvedSystem = service.resolve(7, systemVersion);
        assertEquals(1, resolvedSystem.actorUserId());
        assertEquals(19, resolvedSystem.attributionUserId());
        assertEquals("system", resolvedSystem.role());
    }

    @Test
    void missingSystemAttributionMemberFailsClosed() {
        WorkflowVersion version = version("system", null, 19);
        when(workspaceService.getRole(7, 19)).thenReturn(null);

        WorkflowExecutionException failure = assertThrows(
            WorkflowExecutionException.class,
            () -> service.resolve(7, version));

        assertEquals("actor_unavailable", failure.code());
    }

    @Test
    void executionDiscoversEveryOwnerBeforeTheOuterRuntimeLocksIncludingPaddedActionTypes() {
        WorkflowVersion version = version("user", 17, 19);
        version.setActionsJson("[{\"type\":\" assign_owner \",\"targetUserId\":23},"
            + "{\"type\":\"assign_owner\",\"targetUserId\":11},"
            + "{\"type\":\"create_task\"}]");
        when(workspaceService.lockExecutionPermissions(7, 17, 17, java.util.Set.of(11, 17, 23)))
            .thenReturn(java.util.Set.of(ooo.klae.connex.backend.tenant.Permission.PERSON_UPDATE));
        when(workspaceService.getRole(7, 17)).thenReturn("member");
        when(userMapper.getUserById(17)).thenReturn(user(17));

        WorkflowExecutionPrincipal principal = service.resolveLocked(7, version);

        assertEquals(java.util.Set.of(ooo.klae.connex.backend.tenant.Permission.PERSON_UPDATE),
            principal.lockedPermissions());
        org.mockito.Mockito.verify(workspaceService).lockExecutionPermissions(
            7, 17, 17, java.util.Set.of(11, 17, 23));
    }

    @Test
    void systemExecutionRetainsItsAuditAccountAndTheAttributionMembership() {
        WorkflowVersion version = version("system", null, 19);
        version.setActionsJson("[]");
        when(systemActor.user()).thenReturn(user(1));
        when(workspaceService.getRole(7, 19)).thenReturn("admin");
        when(workspaceService.lockExecutionPermissions(7, 1, 19, java.util.Set.of(19)))
            .thenReturn(java.util.Set.of());
        when(systemActor.permissions()).thenReturn(java.util.Set.of());

        service.resolveLocked(7, version);

        org.mockito.Mockito.verify(workspaceService).lockExecutionPermissions(7, 1, 19, java.util.Set.of(19));
    }

    private static WorkflowVersion version(
            String mode, Integer runAsUserId, Integer createdById) {
        WorkflowVersion version = new WorkflowVersion();
        version.setExecutionMode(mode);
        version.setRunAsUserId(runAsUserId);
        version.setCreatedById(createdById);
        return version;
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
