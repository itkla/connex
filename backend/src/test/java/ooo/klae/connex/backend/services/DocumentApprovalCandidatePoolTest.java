package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Guards the candidate-only batch resolver without altering general permission resolution. */
@ExtendWith(MockitoExtension.class)
class DocumentApprovalCandidatePoolTest {
    private static final int WORKSPACE_ID = 42;
    @Mock WorkspaceMapper workspaces;
    @Mock RoleMapper roles;
    @Mock UserMapper users;
    @InjectMocks WorkspaceService service;

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void authorizationQueryCountDoesNotGrowWithMemberCount(int memberCount) {
        List<MemberDto> members = IntStream.rangeClosed(1, memberCount)
            .mapToObj(id -> member(id, id % 2 == 0 ? "member" : "admin", id % 2 == 0 ? 9 : null))
            .toList();
        List<Integer> ids = members.stream().map(MemberDto::getId).toList();
        when(workspaces.getMembersWithRoles(WORKSPACE_ID)).thenReturn(members);
        when(roles.findAuthorityRolesByWorkspace(WORKSPACE_ID)).thenReturn(List.of(approvalRole()));
        when(users.findAccountDeletionReservedIds(ids)).thenReturn(List.of());

        assertEquals(ids, service.documentApprovalCandidateMembers(WORKSPACE_ID).stream().map(User::getId).toList());

        verify(workspaces).getMembersWithRoles(WORKSPACE_ID);
        verify(roles).findAuthorityRolesByWorkspace(WORKSPACE_ID);
        verify(users).findAccountDeletionReservedIds(ids);
        verifyNoMoreInteractions(workspaces, roles, users);
    }

    @Test
    void unknownAndForeignRolesAndReservedAccountsGrantNoCandidateAuthority() {
        MemberDto pending = member(7, "admin", null);
        pending.setStatus("pending");
        when(workspaces.getMembersWithRoles(WORKSPACE_ID)).thenReturn(List.of(
            member(1, "unresolved", null), member(2, "admin", 99), member(3, "owner", 99),
            member(4, "owner", null), member(5, "admin", null), member(6, "member", 9), pending));
        when(roles.findAuthorityRolesByWorkspace(WORKSPACE_ID)).thenReturn(List.of(approvalRole()));
        when(users.findAccountDeletionReservedIds(List.of(1, 2, 3, 4, 5, 6))).thenReturn(List.of(4));

        List<User> candidates = service.documentApprovalCandidateMembers(WORKSPACE_ID);

        assertEquals(List.of(5, 6), candidates.stream().map(User::getId).toList());
        assertEquals("candidate-5", candidates.getFirst().getUsername());
        assertEquals("Candidate 5", candidates.getFirst().getDisplayName());
        assertEquals("candidate-5@example.test", candidates.getFirst().getEmail());
    }

    private MemberDto member(int id, String builtInRole, Integer roleId) {
        MemberDto member = new MemberDto();
        member.setId(id);
        member.setUsername("candidate-" + id);
        member.setDisplayName("Candidate " + id);
        member.setEmail("candidate-" + id + "@example.test");
        member.setBuiltInRole(builtInRole);
        member.setRoleId(roleId);
        member.setStatus("active");
        return member;
    }

    private WorkspaceRole approvalRole() {
        WorkspaceRole role = new WorkspaceRole();
        role.setId(9);
        role.setWorkspaceId(WORKSPACE_ID);
        role.setPermissions(List.of("DOCUMENT_APPROVE"));
        return role;
    }
}
