package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Pins actor, workspace, organization, membership, and request lock ordering for APPI writes.
 */
@ExtendWith(MockitoExtension.class)
class DataSubjectRequestControlOperationsTest {
    private static final int ORG_ID = 5;
    private static final int WORKSPACE_ID = 8;
    private static final int ACTOR_ID = 2;
    private static final WorkspaceLifecycleRef WORKSPACE =
        new WorkspaceLifecycleRef(WORKSPACE_ID, ORG_ID, "Subject", "subject", "active");

    @Mock private DataSubjectRequestMapper dataSubjectRequestMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantLifecycleControlMapper tenantLifecycleControlMapper;
    @Mock private UserMapper userMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    private DataSubjectRequestControlOperations operations;

    @BeforeEach
    void setUp() {
        operations = new DataSubjectRequestControlOperations(
            dataSubjectRequestMapper,
            workspaceMapper,
            tenantLifecycleControlMapper,
            userMapper,
            orgMemberService,
            auditService,
            sessionSecurityService);
        lenient().when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
    }

    @Test
    void anUnlinkedRequestIsRefusedWhileItsOrganizationIsTearingDown() {
        DataSubjectRequest request = request(null);
        when(tenantLifecycleControlMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(null);

        assertThrows(
            ConflictException.class,
            () -> operations.create(ORG_ID, ACTOR_ID, request));

        verify(tenantLifecycleControlMapper).lockActiveOrganizationForShare(ORG_ID);
        verify(dataSubjectRequestMapper, never()).insert(any(DataSubjectRequest.class));
    }

    @Test
    void preliminaryAccessDeniesMissingOrganizationsUniformly() {
        when(tenantLifecycleControlMapper.isOrgAdminForLifecycle(ORG_ID, ACTOR_ID))
            .thenReturn(false);

        assertThrows(
            ForbiddenException.class,
            () -> operations.requireMutationAccess(ORG_ID, ACTOR_ID));

        verify(tenantLifecycleControlMapper, never()).findOrganization(ORG_ID);
        verify(sessionSecurityService, never()).requireRecentAuthentication(ACTOR_ID);
    }

    @Test
    void anUnlinkedRequestLocksTheActiveOrganizationRootBeforeItIsInserted() {
        DataSubjectRequest request = request(null);
        when(tenantLifecycleControlMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(tenantLifecycleControlMapper.lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID))
            .thenReturn(ACTOR_ID);
        when(dataSubjectRequestMapper.findById(ORG_ID, request.getId())).thenReturn(request);

        operations.create(ORG_ID, ACTOR_ID, request);

        InOrder order = inOrder(
            userMapper,
            tenantLifecycleControlMapper,
            sessionSecurityService,
            dataSubjectRequestMapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(tenantLifecycleControlMapper).lockActiveOrganizationForShare(ORG_ID);
        order.verify(tenantLifecycleControlMapper)
            .lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(dataSubjectRequestMapper).insert(request);
        verify(tenantLifecycleControlMapper, never()).lockWorkspaceForShare(WORKSPACE_ID);
    }

    @Test
    void aWorkspaceLinkedRequestStillLocksItsOwnWorkspace() {
        DataSubjectRequest request = request(WORKSPACE_ID);
        when(tenantLifecycleControlMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(tenantLifecycleControlMapper.lockWorkspaceForShare(WORKSPACE_ID))
            .thenReturn(WORKSPACE);
        when(tenantLifecycleControlMapper.lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID))
            .thenReturn(ACTOR_ID);
        when(dataSubjectRequestMapper.findById(ORG_ID, request.getId())).thenReturn(request);

        operations.create(ORG_ID, ACTOR_ID, request);

        InOrder order = inOrder(userMapper, tenantLifecycleControlMapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(tenantLifecycleControlMapper).lockWorkspaceForShare(WORKSPACE_ID);
        order.verify(tenantLifecycleControlMapper).lockActiveOrganizationForShare(ORG_ID);
        order.verify(tenantLifecycleControlMapper)
            .lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID);
    }

    @Test
    void subjectValidationRetainsControlRootsInGlobalOrder() {
        when(tenantLifecycleControlMapper.lockWorkspaceForShare(WORKSPACE_ID))
            .thenReturn(WORKSPACE);
        when(tenantLifecycleControlMapper.lockActiveOrganizationForShare(ORG_ID))
            .thenReturn(ORG_ID);

        String result = operations.withLockedSubjectRoots(
            ORG_ID,
            ACTOR_ID,
            Set.of(WORKSPACE_ID),
            () -> "locked");

        assertEquals("locked", result);
        InOrder order = inOrder(userMapper, tenantLifecycleControlMapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(tenantLifecycleControlMapper).lockWorkspaceForShare(WORKSPACE_ID);
        order.verify(tenantLifecycleControlMapper).lockActiveOrganizationForShare(ORG_ID);
    }

    @Test
    void aMissingForeignOrTearingDownWorkspaceIsAConflictBeforeAuthorizationRecheck() {
        DataSubjectRequest request = request(WORKSPACE_ID);
        when(tenantLifecycleControlMapper.lockWorkspaceForShare(WORKSPACE_ID))
            .thenReturn(new WorkspaceLifecycleRef(
                WORKSPACE_ID,
                ORG_ID + 1,
                "Foreign",
                "foreign",
                "active"));

        assertThrows(
            ConflictException.class,
            () -> operations.create(ORG_ID, ACTOR_ID, request));

        verify(tenantLifecycleControlMapper, never())
            .lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID);
        verify(dataSubjectRequestMapper, never()).insert(any(DataSubjectRequest.class));
        verifyNoInteractions(auditService, sessionSecurityService);
    }

    @Test
    void staleUpdateIsRefusedAfterTheExactRequestLock() {
        DataSubjectRequest before = request(WORKSPACE_ID);
        before.setId(14);
        DataSubjectRequest desired = request(WORKSPACE_ID);
        desired.setId(14);
        desired.setStatus("closed");
        DataSubjectRequest changed = request(WORKSPACE_ID);
        changed.setId(14);
        changed.setStatus("in_progress");
        when(tenantLifecycleControlMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(tenantLifecycleControlMapper.lockWorkspaceForShare(WORKSPACE_ID))
            .thenReturn(WORKSPACE);
        when(tenantLifecycleControlMapper.lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID))
            .thenReturn(ACTOR_ID);
        when(dataSubjectRequestMapper.findByIdForUpdate(ORG_ID, 14)).thenReturn(changed);

        ConflictException exception = assertThrows(
            ConflictException.class,
            () -> operations.update(ORG_ID, 14, ACTOR_ID, before, desired));

        assertEquals("Data-subject request changed; retry the update", exception.getMessage());
        InOrder order = inOrder(
            userMapper,
            tenantLifecycleControlMapper,
            sessionSecurityService,
            dataSubjectRequestMapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(tenantLifecycleControlMapper).lockWorkspaceForShare(WORKSPACE_ID);
        order.verify(tenantLifecycleControlMapper).lockActiveOrganizationForShare(ORG_ID);
        order.verify(tenantLifecycleControlMapper)
            .lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(dataSubjectRequestMapper).findByIdForUpdate(ORG_ID, 14);
        verify(dataSubjectRequestMapper, never()).update(any(DataSubjectRequest.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void updateLocksPreviousAndRequestedWorkspaceRootsInAscendingOrder() {
        DataSubjectRequest before = request(11);
        before.setId(14);
        DataSubjectRequest desired = request(WORKSPACE_ID);
        desired.setId(14);
        WorkspaceLifecycleRef previous =
            new WorkspaceLifecycleRef(11, ORG_ID, "Previous", "previous", "active");
        when(tenantLifecycleControlMapper.lockWorkspaceForShare(WORKSPACE_ID))
            .thenReturn(WORKSPACE);
        when(tenantLifecycleControlMapper.lockWorkspaceForShare(11)).thenReturn(previous);
        when(tenantLifecycleControlMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(tenantLifecycleControlMapper.lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID))
            .thenReturn(ACTOR_ID);
        when(dataSubjectRequestMapper.findByIdForUpdate(ORG_ID, 14)).thenReturn(before);
        when(dataSubjectRequestMapper.update(desired)).thenReturn(1);
        when(dataSubjectRequestMapper.findById(ORG_ID, 14)).thenReturn(desired);

        operations.update(ORG_ID, 14, ACTOR_ID, before, desired);

        InOrder order = inOrder(userMapper, tenantLifecycleControlMapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(tenantLifecycleControlMapper).lockWorkspaceForShare(WORKSPACE_ID);
        order.verify(tenantLifecycleControlMapper).lockWorkspaceForShare(11);
        order.verify(tenantLifecycleControlMapper).lockActiveOrganizationForShare(ORG_ID);
    }

    private static DataSubjectRequest request(Integer workspaceId) {
        DataSubjectRequest request = new DataSubjectRequest();
        request.setOrgId(ORG_ID);
        request.setRequestType("disclosure");
        request.setStatus("received");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        request.setSubjectWorkspaceId(workspaceId);
        return request;
    }
}
