package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AddMemberRequest;
import ooo.klae.connex.backend.dto.CreateInviteRequest;
import ooo.klae.connex.backend.dto.CreateWorkspaceRequest;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.MyWorkspacesDto;
import ooo.klae.connex.backend.dto.UpdateMemberRoleRequest;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.InviteService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

/**
 * Workspace membership and switching. Lists the caller's workspaces, creates a
 * new owned workspace, and switches the active workspace (persisted + cookie).
 */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {
    private final WorkspaceService workspaceService;
    private final InviteService inviteService;
    private final AuthService authService;

    @GetMapping
    public MyWorkspacesDto myWorkspaces() {
        int userId = authService.getCurrentUser().getId();
        return new MyWorkspacesDto(
            workspaceService.getMembershipsForCurrentUser(),
            workspaceService.defaultWorkspaceIdFor(userId)
        );
    }

    @PostMapping
    public WorkspaceMembershipDto create(@Valid @RequestBody CreateWorkspaceRequest request,
            HttpServletResponse response) {
        int userId = authService.getCurrentUser().getId();
        WorkspaceMembershipDto created = workspaceService.createWorkspace(request.getName(), userId);
        workspaceService.rememberActive(userId, created.getId());
        WorkspaceCookie.set(response, created.getId());
        return created;
    }

    @PostMapping("/{id}/switch")
    public void switchWorkspace(@PathVariable int id, HttpServletResponse response) {
        int userId = authService.getCurrentUser().getId();
        workspaceService.requireMember(id, userId);
        workspaceService.rememberActive(userId, id);
        WorkspaceCookie.set(response, id);
    }

    @PostMapping("/{id}/invites")
    public InviteDto invite(@PathVariable int id, @Valid @RequestBody CreateInviteRequest request) {
        return inviteService.createInvite(id, authService.getCurrentUser(), request.getEmail(), request.getRole());
    }

    @GetMapping("/{id}/invites")
    public List<InviteDto> invites(@PathVariable int id) {
        return inviteService.listInvites(id, authService.getCurrentUser().getId());
    }

    @DeleteMapping("/{id}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@PathVariable int id, @PathVariable int inviteId) {
        inviteService.revokeInvite(id, inviteId, authService.getCurrentUser().getId());
    }

    @GetMapping("/{id}/members")
    public List<MemberDto> members(@PathVariable int id) {
        return workspaceService.getMembersWithRoles(id, authService.getCurrentUser().getId());
    }

    @PostMapping("/{id}/members")
    public MemberDto addMember(@PathVariable int id, @Valid @RequestBody AddMemberRequest request) {
        return inviteService.addExistingMember(id, authService.getCurrentUser().getId(), request.getEmail(), request.getRole());
    }

    @PatchMapping("/{id}/members/{userId}")
    public MemberDto updateMemberRole(@PathVariable int id, @PathVariable int userId,
            @Valid @RequestBody UpdateMemberRoleRequest request) {
        return workspaceService.changeMemberRole(id, authService.getCurrentUser().getId(), userId, request.getRole());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable int id, @PathVariable int userId) {
        workspaceService.removeMember(id, authService.getCurrentUser().getId(), userId);
    }
}
