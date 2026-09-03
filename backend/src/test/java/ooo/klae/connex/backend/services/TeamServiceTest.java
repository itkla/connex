package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Team;
import ooo.klae.connex.backend.beans.TeamMember;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.TeamMemberRequest;
import ooo.klae.connex.backend.dto.TeamRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.TeamMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int ACTOR_ID = 11;
    private static final int TEAM_ID = 31;

    @Mock private TeamMapper teamMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;

    private TeamService service;

    @BeforeEach
    void setUp() {
        service = new TeamService(teamMapper, workspaceService, authService, auditService);
        User actor = user(ACTOR_ID, "Actor");
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(authService.getCurrentUser()).thenReturn(actor);
        lenient().when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(actor));
        lenient().when(workspaceService.areActiveMembers(eq(WORKSPACE_ID), any())).thenReturn(true);
        lenient().when(teamMapper.getMembersForTeams(eq(WORKSPACE_ID), any())).thenReturn(List.of());
    }

    @Test
    void archivedNameReuseReachesInsertWithoutAServiceSideNameBlock() {
        when(teamMapper.insert(any(Team.class))).thenAnswer(invocation -> {
            Team team = invocation.getArgument(0);
            team.setId(TEAM_ID);
            return 1;
        });
        when(teamMapper.getById(WORKSPACE_ID, TEAM_ID)).thenReturn(team(null));

        service.create(new TeamRequest("Sales", null, null));

        verify(teamMapper).insert(any(Team.class));
    }

    @Test
    void nonActiveWorkspaceMemberCannotReceiveASeat() {
        when(workspaceService.areActiveMembers(WORKSPACE_ID, List.of(22))).thenReturn(false);

        assertThrows(BadRequestException.class,
            () -> service.addMember(TEAM_ID, new TeamMemberRequest(22, "member")));

        verifyNoInteractions(teamMapper);
    }

    @Test
    void updateRefusesManagerWhoDoesNotAlreadyHoldASeat() {
        when(teamMapper.getByIdForUpdate(WORKSPACE_ID, TEAM_ID)).thenReturn(team(null));
        when(teamMapper.lockMember(WORKSPACE_ID, TEAM_ID, 22)).thenReturn(null);

        assertThrows(BadRequestException.class,
            () -> service.update(TEAM_ID, new TeamRequest("Sales", null, 22)));

        verify(teamMapper, never()).update(any(Team.class));
    }

    @Test
    void createWithManagerLocksMembershipAndCreatesTheManagerSeat() {
        User manager = user(22, "Manager");
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(user(ACTOR_ID, "Actor"), manager));
        when(teamMapper.insert(any(Team.class))).thenAnswer(invocation -> {
            Team team = invocation.getArgument(0);
            team.setId(TEAM_ID);
            return 1;
        });
        when(teamMapper.getById(WORKSPACE_ID, TEAM_ID)).thenReturn(team(22));
        TeamMember stored = new TeamMember();
        stored.setWorkspaceId(WORKSPACE_ID);
        stored.setTeamId(TEAM_ID);
        stored.setUserId(22);
        stored.setRole("manager");
        when(teamMapper.getMembersForTeams(WORKSPACE_ID, List.of(TEAM_ID))).thenReturn(List.of(stored));

        service.create(new TeamRequest("Sales", null, 22));

        verify(workspaceService).lockAndRequirePermissions(eq(WORKSPACE_ID), argThat(locks ->
            Set.of(Permission.TEAM_MANAGE).equals(locks.get(ACTOR_ID))
                && Set.of().equals(locks.get(22))));
        ArgumentCaptor<TeamMember> member = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMapper).upsertMember(member.capture());
        assertEquals("manager", member.getValue().getRole());
        assertEquals(22, member.getValue().getUserId());
    }

    @Test
    void archiveIsIdempotent() {
        Team archived = team(null);
        archived.setArchivedAt("2026-09-02T10:00:00");
        when(teamMapper.getByIdForUpdate(WORKSPACE_ID, TEAM_ID)).thenReturn(archived);

        service.archive(TEAM_ID);

        verify(teamMapper, never()).archive(WORKSPACE_ID, TEAM_ID);
        verify(auditService).record(
            "team.archive", "team", TEAM_ID, "Sales", "Team was already archived",
            java.util.Map.of("idempotent", true));
    }

    private static Team team(Integer managerUserId) {
        Team team = new Team();
        team.setId(TEAM_ID);
        team.setWorkspaceId(WORKSPACE_ID);
        team.setName("Sales");
        team.setManagerUserId(managerUserId);
        return team;
    }

    private static User user(int id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        return user;
    }
}
