package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.AiStandingApprovalPolicy;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.*;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrgMemberService;

@ExtendWith(MockitoExtension.class)
class AiStandingApprovalControlOperationsTest {
    @Mock AiStandingApprovalPolicyMapper policies;
    @Mock UserMapper users;
    @Mock WorkspaceMapper workspaces;
    @Mock OrganizationMapper organizations;
    @Mock OrgMemberService members;
    @Mock AuditService audit;
    @InjectMocks AiStandingApprovalControlOperations operations;

    private AiStandingApprovalPolicy policy;

    @BeforeEach
    void policy() {
        policy = new AiStandingApprovalPolicy();
        policy.setOrgId(3);
        policy.setStateVersion(1);
        policy.setKillEpoch(1);
        policy.setMaximumDailyCap(100);
        policy.setMaximumPerRunCap(10);
    }

    private AiStandingApprovalPolicyRequest request(boolean enabled, String version) {
        return new AiStandingApprovalPolicyRequest(enabled, List.of("remove_tag"), 50, 5, version);
    }

    private void authorizeWrite() {
        when(users.isAccountDeletionReservedForShare(7)).thenReturn(false);
        when(workspaces.lockActiveWorkspaceForShare(9)).thenReturn(3);
        when(organizations.lockActiveByIdForShare(3)).thenReturn(3);
        when(workspaces.lockActiveMembership(9, 7)).thenReturn(7);
        when(policies.lockForUpdate(3)).thenReturn(policy);
    }

    @Test
    void missingPolicyReadIsDisabledAndPerformsNoProvisioning() {
        when(workspaces.getOrgId(9)).thenReturn(3);
        when(workspaces.isMember(9, 7)).thenReturn(true);
        var read = operations.read(9, 3, 7);
        assertFalse(read.enabled());
        assertFalse(read.authoringEnabled());
        assertFalse(read.provisioned());
        assertNull(read.stateVersion());
        assertEquals(List.of(), read.allowedTools());
        verify(policies).get(3);
        verifyNoMoreInteractions(policies);
        verifyNoInteractions(audit);
    }

    @Test
    void killAndReenableNeverRestoreOldAuthorityAndAuditIsLast() {
        authorizeWrite();
        when(policies.replace(policy)).thenReturn(1);
        var killed = operations.replace(9, 3, 7, request(false, "1"));
        assertEquals("2", killed.killEpoch());
        assertEquals("2", killed.stateVersion());
        var restored = operations.replace(9, 3, 7, request(true, "2"));
        assertEquals("2", restored.killEpoch());
        assertEquals("3", restored.stateVersion());
        assertTrue(restored.enabled());
        assertFalse(restored.authoringEnabled());
        var order = inOrder(users, workspaces, organizations, members, policies, audit);
        order.verify(users).isAccountDeletionReservedForShare(7);
        order.verify(workspaces).lockActiveWorkspaceForShare(9);
        order.verify(workspaces).lockActiveMembership(9, 7);
        order.verify(organizations).lockActiveByIdForShare(3);
        order.verify(members).requireOrgAdminWithOrganizationRetained(3, 7);
        order.verify(policies).lockForUpdate(3);
        order.verify(policies).replace(policy);
        order.verify(audit).recordStrictScoped(anyString(), anyString(), eq(3), isNull(), eq(3), anyString(),
                anyString(), anyMap());
    }

    @Test
    void staleMissingAndExhaustedAuthorityCannotBeReplaced() {
        authorizeWrite();
        assertThrows(ConflictException.class, () -> operations.replace(9, 3, 7, request(true, "2")));
        policy.setKillEpoch(Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> operations.replace(9, 3, 7, request(false, "1")));
        when(policies.lockForUpdate(3)).thenReturn(null);
        assertThrows(ConflictException.class, () -> operations.replace(9, 3, 7, request(true, "1")));
        verify(policies, never()).replace(any());
        verifyNoInteractions(audit);
    }

    @Test
    void otherOrganizationCannotReadOrWriteEvenAsItsAdministrator() {
        when(workspaces.getOrgId(9)).thenReturn(4);
        assertThrows(ForbiddenException.class, () -> operations.read(9, 3, 7));
        when(users.isAccountDeletionReservedForShare(7)).thenReturn(false);
        when(workspaces.lockActiveWorkspaceForShare(9)).thenReturn(4);
        assertThrows(ForbiddenException.class, () -> operations.replace(9, 3, 7, request(true, "1")));
        verifyNoInteractions(policies, members, audit);
    }
}
