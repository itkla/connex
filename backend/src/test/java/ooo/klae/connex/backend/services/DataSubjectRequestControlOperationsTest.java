package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Pins the lifecycle fence that every data-subject request write serialises
 * against: a workspace-linked request locks its workspace, and a request with no
 * workspace link locks the organization root instead, so neither can be created
 * into a teardown window that is about to erase it.
 */
@ExtendWith(MockitoExtension.class)
class DataSubjectRequestControlOperationsTest {
    private static final int ORG_ID = 5;
    private static final int WORKSPACE_ID = 8;
    private static final int ACTOR_ID = 2;

    @Mock private DataSubjectRequestMapper dataSubjectRequestMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantLifecycleControlMapper tenantLifecycleControlMapper;
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
            orgMemberService,
            auditService,
            sessionSecurityService);
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
    void anUnlinkedRequestLocksTheActiveOrganizationRootBeforeItIsInserted() {
        DataSubjectRequest request = request(null);
        when(tenantLifecycleControlMapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(dataSubjectRequestMapper.findById(ORG_ID, request.getId())).thenReturn(request);

        operations.create(ORG_ID, ACTOR_ID, request);

        InOrder order = inOrder(tenantLifecycleControlMapper, dataSubjectRequestMapper);
        order.verify(tenantLifecycleControlMapper).lockActiveOrganizationForShare(ORG_ID);
        order.verify(dataSubjectRequestMapper).insert(request);
        verify(workspaceMapper, never()).lockActiveWorkspaceInOrgForShare(ORG_ID, WORKSPACE_ID);
    }

    @Test
    void aWorkspaceLinkedRequestStillLocksItsOwnWorkspace() {
        DataSubjectRequest request = request(WORKSPACE_ID);
        when(workspaceMapper.lockActiveWorkspaceInOrgForShare(ORG_ID, WORKSPACE_ID))
            .thenReturn(WORKSPACE_ID);
        when(dataSubjectRequestMapper.findById(ORG_ID, request.getId())).thenReturn(request);

        operations.create(ORG_ID, ACTOR_ID, request);

        verify(workspaceMapper).lockActiveWorkspaceInOrgForShare(ORG_ID, WORKSPACE_ID);
        verify(tenantLifecycleControlMapper, never()).lockActiveOrganizationForShare(ORG_ID);
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
