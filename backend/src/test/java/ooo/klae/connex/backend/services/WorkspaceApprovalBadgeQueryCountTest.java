package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.ExportPermissions;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Keeps roster approval hints bounded while retaining the effective role grant ceiling. */
@ExtendWith(MockitoExtension.class)
class WorkspaceApprovalBadgeQueryCountTest {
    private static final int WORKSPACE = 42;
    private static final int ORGANIZATION = 43;
    private static final int ACTOR = 1;
    @Mock private WorkspaceMapper workspaces;
    @Mock private UserMapper users;
    @Mock private RoleMapper roles;
    @Mock private PrivilegedAccountService privilegedAccounts;
    @InjectMocks private WorkspaceService service;

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void exportPolicyLookupDoesNotGrowWithRosterSize(int targets) {
        List<MemberDto> roster = roster(targets, new ExportPermissions(true, true, true));
        List<MemberDto> result = service.getMembersWithRoles(WORKSPACE, ACTOR);

        assertEquals(roster.size(), result.size());
        assertTrue(result.stream().filter(member -> member.getId() != ACTOR)
            .allMatch(member -> Boolean.TRUE.equals(member.getPasskeyApprovalNeeded())));
        verify(workspaces).getExportPermissions(WORKSPACE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exportPolicyStillParticipatesInBothSidesOfTheApprovalGrantCeiling(boolean adminExport) {
        roster(1, new ExportPermissions(false, adminExport, true));
        List<MemberDto> result = service.getMembersWithRoles(WORKSPACE, ACTOR);

        if (adminExport) {
            assertNull(result.get(1).getPasskeyApprovalNeeded());
        } else {
            assertEquals(Boolean.TRUE, result.get(1).getPasskeyApprovalNeeded());
        }
        verify(workspaces).getExportPermissions(WORKSPACE);
    }

    private List<MemberDto> roster(int targets, ExportPermissions policy) {
        List<MemberDto> members = IntStream.rangeClosed(ACTOR, targets + ACTOR).mapToObj(id -> {
            MemberDto member = new MemberDto();
            member.setId(id);
            member.setBuiltInRole(id == ACTOR ? "owner" : "admin");
            member.setRole(member.getBuiltInRole());
            member.setStatus("active");
            return member;
        }).toList();
        List<Integer> ids = members.stream().map(MemberDto::getId).toList();
        WorkspaceMember actor = new WorkspaceMember();
        actor.setWorkspaceId(WORKSPACE);
        actor.setUserId(ACTOR);
        actor.setRole("owner");
        actor.setStatus("active");
        when(workspaces.isMember(WORKSPACE, ACTOR)).thenReturn(true);
        when(workspaces.getMembersWithRoles(WORKSPACE)).thenReturn(members);
        when(workspaces.getAuthorizationMembership(WORKSPACE, ACTOR)).thenReturn(actor);
        when(workspaces.getOrgId(WORKSPACE)).thenReturn(ORGANIZATION);
        when(workspaces.getExportPermissions(WORKSPACE)).thenReturn(policy);
        when(users.findAccountDeletionReservedIds(ids)).thenReturn(List.of());
        when(roles.findAuthorityRolesByWorkspace(WORKSPACE)).thenReturn(List.of());
        when(privilegedAccounts.hasPasskeyCoveredInOrganization(ACTOR, ORGANIZATION)).thenReturn(true);
        when(privilegedAccounts.passkeyApprovalNeeded(ORGANIZATION, ids)).thenReturn(Set.copyOf(ids));
        return members;
    }
}
