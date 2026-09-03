package ooo.klae.connex.backend.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Team;
import ooo.klae.connex.backend.beans.TeamMember;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.TeamDto;
import ooo.klae.connex.backend.dto.TeamMemberDto;
import ooo.klae.connex.backend.dto.TeamMemberRequest;
import ooo.klae.connex.backend.dto.TeamRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.TeamMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Business logic for workspace-scoped teams and active-member seats. */
@Service
@RequiredArgsConstructor
public class TeamService {
    private static final String MEMBER = "member";
    private static final String MANAGER = "manager";
    private static final String UNAVAILABLE_MEMBER = "Unavailable member";
    private static final Set<String> ROLES = Set.of(MEMBER, MANAGER);
    private static final Set<String> AUDIT_FIELDS = Set.of(
        "name", "description", "managerUserId", "archivedAt");

    private final TeamMapper teamMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;

    /** Returns active teams, or all teams when explicitly requested, for the active workspace. */
    public List<TeamDto> list(boolean includeArchived) {
        int workspaceId = requireActiveWorkspaceMembership();
        List<Team> teams = teamMapper.getAll(workspaceId, includeArchived);
        return hydrate(workspaceId, teams);
    }

    /** Returns one team, including an archived team, in the active workspace. */
    public TeamDto get(int id) {
        int workspaceId = requireActiveWorkspaceMembership();
        return hydrate(workspaceId, List.of(requireTeam(workspaceId, id))).getFirst();
    }

    /** Creates a team and automatically seats its optional initial manager. */
    @Transactional
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto create(TeamRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        lockMutationMemberships(workspaceId, actorId, request.managerUserId());
        Team team = new Team();
        team.setWorkspaceId(workspaceId);
        applyRequest(team, request);
        try {
            teamMapper.insert(team);
        } catch (DuplicateKeyException exception) {
            throw duplicateName(team.getName());
        }
        if (team.getManagerUserId() != null) {
            teamMapper.upsertMember(member(workspaceId, team.getId(), team.getManagerUserId(), MANAGER));
        }
        Team saved = requireTeam(workspaceId, team.getId());
        auditService.record("team.create", "team", saved.getId(), saved.getName(),
            "Created team " + saved.getName(), auditService.diff(null, saved, AUDIT_FIELDS));
        return hydrate(workspaceId, List.of(saved)).getFirst();
    }

    /** Replaces a team's editable fields and synchronizes its singular manager seat. */
    @Transactional
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto update(int id, TeamRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        lockMutationMemberships(workspaceId, actorId, request.managerUserId());
        Team team = requireMutableTeamForUpdate(workspaceId, id);
        Team before = copy(team);
        Integer managerUserId = request.managerUserId();
        if (managerUserId != null
                && teamMapper.lockMember(workspaceId, id, managerUserId) == null) {
            throw new BadRequestException("Team manager must already hold a seat on the team");
        }
        applyRequest(team, request);
        try {
            if (teamMapper.update(team) == 0) {
                throw teamNotFound();
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName(team.getName());
        }
        teamMapper.demoteManagers(workspaceId, id);
        if (managerUserId != null) {
            teamMapper.upsertMember(member(workspaceId, id, managerUserId, MANAGER));
        }
        Team after = requireTeam(workspaceId, id);
        auditService.record("team.update", "team", id, after.getName(),
            "Updated team " + after.getName(), auditService.diff(before, after, AUDIT_FIELDS));
        return hydrate(workspaceId, List.of(after)).getFirst();
    }

    /** Soft-archives a team while preserving its seats; repeated archive requests are harmless. */
    @Transactional
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto archive(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        lockMutationMemberships(workspaceId, actorId, null);
        Team before = requireTeamForUpdate(workspaceId, id);
        if (before.getArchivedAt() == null) {
            teamMapper.archive(workspaceId, id);
            Team after = requireTeam(workspaceId, id);
            auditService.record("team.archive", "team", id, after.getName(),
                "Archived team " + after.getName(), auditService.diff(before, after, AUDIT_FIELDS));
            return hydrate(workspaceId, List.of(after)).getFirst();
        }
        auditService.record("team.archive", "team", id, before.getName(),
            "Team was already archived", Map.of("idempotent", true));
        return hydrate(workspaceId, List.of(before)).getFirst();
    }

    /** Adds a seat or replaces its role after locking the target's active workspace membership. */
    @Transactional
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto addMember(int id, TeamMemberRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        if (!ROLES.contains(request.role())) {
            throw new BadRequestException("Team role must be member or manager");
        }
        lockMutationMemberships(workspaceId, actorId, request.userId());
        Team team = requireMutableTeamForUpdate(workspaceId, id);
        if (MANAGER.equals(request.role())) {
            teamMapper.demoteManagers(workspaceId, id);
            team.setManagerUserId(request.userId());
            teamMapper.update(team);
        } else if (team.getManagerUserId() != null
                && team.getManagerUserId() == request.userId()) {
            team.setManagerUserId(null);
            teamMapper.update(team);
        }
        teamMapper.upsertMember(member(workspaceId, id, request.userId(), request.role()));
        auditService.record("team.member.add", "team", id, team.getName(),
            "Added or updated a team member",
            Map.of("userId", request.userId(), "role", request.role()));
        return hydrate(workspaceId, List.of(requireTeam(workspaceId, id))).getFirst();
    }

    /** Removes a team seat and clears the manager reference when it names that user. */
    @Transactional
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto removeMember(int id, int userId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        lockMutationMemberships(workspaceId, actorId, userId);
        Team team = requireMutableTeamForUpdate(workspaceId, id);
        if (teamMapper.removeMember(workspaceId, id, userId) == 0) {
            throw new ResourceNotFoundException("Team member not found");
        }
        teamMapper.clearManager(workspaceId, id, userId);
        auditService.record("team.member.remove", "team", id, team.getName(),
            "Removed a team member", Map.of("userId", userId));
        return hydrate(workspaceId, List.of(requireTeam(workspaceId, id))).getFirst();
    }

    private int requireActiveWorkspaceMembership() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        workspaceService.requireMember(workspaceId, authService.getCurrentUser().getId());
        return workspaceId;
    }

    private void lockMutationMemberships(int workspaceId, int actorId, Integer targetUserId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.TEAM_MANAGE);
        if (targetUserId != null && targetUserId != actorId
                && !workspaceService.areActiveMembers(workspaceId, List.of(targetUserId))) {
            throw new BadRequestException("Team members must be active members of this workspace");
        }
        Map<Integer, Set<Permission>> required = new HashMap<>();
        required.put(actorId, Set.of(Permission.TEAM_MANAGE));
        if (targetUserId != null && targetUserId != actorId) {
            required.put(targetUserId, Set.of());
        }
        workspaceService.lockAndRequirePermissions(workspaceId, required);
    }

    private Team requireMutableTeamForUpdate(int workspaceId, int id) {
        Team team = requireTeamForUpdate(workspaceId, id);
        if (team.getArchivedAt() != null) {
            throw new BadRequestException("Archived teams cannot be changed");
        }
        return team;
    }

    private Team requireTeamForUpdate(int workspaceId, int id) {
        Team team = teamMapper.getByIdForUpdate(workspaceId, id);
        if (team == null) {
            throw teamNotFound();
        }
        return team;
    }

    private Team requireTeam(int workspaceId, int id) {
        Team team = teamMapper.getById(workspaceId, id);
        if (team == null) {
            throw teamNotFound();
        }
        return team;
    }

    private void applyRequest(Team team, TeamRequest request) {
        team.setName(request.name().trim());
        team.setDescription(normalizeDescription(request.description()));
        team.setManagerUserId(request.managerUserId());
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalized = description.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static TeamMember member(int workspaceId, int teamId, int userId, String role) {
        TeamMember member = new TeamMember();
        member.setWorkspaceId(workspaceId);
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    private static Team copy(Team source) {
        Team copy = new Team();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setManagerUserId(source.getManagerUserId());
        copy.setArchivedAt(source.getArchivedAt());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private List<TeamDto> hydrate(int workspaceId, List<Team> teams) {
        if (teams.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> memberNames = workspaceService.getMembers(workspaceId).stream()
            .collect(Collectors.toMap(User::getId, User::getDisplayName));
        Map<Integer, List<TeamMemberDto>> membersByTeam = teamMapper.getMembersForTeams(
                workspaceId, teams.stream().map(Team::getId).toList()).stream()
            .collect(Collectors.groupingBy(
                TeamMember::getTeamId,
                Collectors.mapping(
                    member -> new TeamMemberDto(
                        member.getUserId(),
                        memberNames.getOrDefault(member.getUserId(), UNAVAILABLE_MEMBER),
                        member.getRole()),
                    Collectors.toList())));
        return teams.stream()
            .map(team -> new TeamDto(
                team.getId(), team.getName(), team.getDescription(), team.getManagerUserId(),
                membersByTeam.getOrDefault(team.getId(), List.of()), team.getArchivedAt()))
            .toList();
    }

    private static DuplicateResourceException duplicateName(String name) {
        return new DuplicateResourceException("name", "An active team named " + name + " already exists");
    }

    private static ResourceNotFoundException teamNotFound() {
        return new ResourceNotFoundException("Team not found");
    }
}
