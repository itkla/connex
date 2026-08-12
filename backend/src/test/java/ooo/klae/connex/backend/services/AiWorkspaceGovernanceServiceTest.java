package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.dto.AiWorkspaceGovernanceRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiWorkspaceGovernanceMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

class AiWorkspaceGovernanceServiceTest {
    private final AiWorkspaceGovernanceMapper governanceMapper =
            mock(AiWorkspaceGovernanceMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final OrgMemberService orgMemberService = mock(OrgMemberService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
    private final AiWorkspaceGovernanceService service = new AiWorkspaceGovernanceService(
            governanceMapper,
            workspaceService,
            orgMemberService,
            auditService,
            userMapper,
            workspaceMapper,
            organizationMapper,
            mock(ooo.klae.connex.backend.ai.assistant.AiAssistantAccessFence.class));

    @Test
    void permissionRevokedBetweenPrecheckAndLockedGovernanceWriteBlocksMutation() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(11)).thenReturn(11);
        when(workspaceMapper.lockActiveWorkspaceForShare(7)).thenReturn(3);
        when(workspaceMapper.lockActiveMembership(7, 11)).thenReturn(11);
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);
        doThrow(new ForbiddenException("Requires an organization administrator role"))
                .when(orgMemberService).requireOrgAdminForUpdate(3, 11);

        assertThrows(
                ForbiddenException.class,
                () -> service.save(7, 11, new AiWorkspaceGovernanceRequest(true, 6)));

        InOrder order = inOrder(
                orgMemberService,
                userMapper,
                workspaceMapper,
                organizationMapper);
        order.verify(orgMemberService).requireOrgAdmin(3, 11);
        order.verify(userMapper).lockByIdForShare(11);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(7);
        order.verify(organizationMapper).lockActiveByIdForShare(3);
        order.verify(workspaceMapper).lockActiveMembership(7, 11);
        order.verify(orgMemberService).requireOrgAdminForUpdate(3, 11);
        verify(governanceMapper, never()).upsert(7, true, 6);
        verifyNoInteractions(auditService);
    }
}
