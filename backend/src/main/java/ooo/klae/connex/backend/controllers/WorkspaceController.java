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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AddAllowedDomainRequest;
import ooo.klae.connex.backend.dto.AddMemberRequest;
import ooo.klae.connex.backend.dto.CreateInviteLinkRequest;
import ooo.klae.connex.backend.dto.CreateInviteRequest;
import ooo.klae.connex.backend.dto.CreateWorkspaceRequest;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.dto.InviteLinkDto;
import ooo.klae.connex.backend.dto.InviteResultDto;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.MyWorkspacesDto;
import ooo.klae.connex.backend.dto.UpdateMemberRoleRequest;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.dto.WorkspaceSelectionDto;
import ooo.klae.connex.backend.services.AllowedDomainService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.InviteLinkService;
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
    private final InviteLinkService inviteLinkService;
    private final AllowedDomainService allowedDomainService;
    private final AuthService authService;
    private final WorkspaceCookie workspaceCookie;

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
        workspaceCookie.set(response, created.getId());
        return created;
    }

    @PostMapping("/{id}/switch")
    public void switchWorkspace(@PathVariable int id, HttpServletResponse response) {
        int userId = authService.getCurrentUser().getId();
        workspaceService.requireMember(id, userId);
        workspaceService.rememberActive(userId, id);
        workspaceCookie.set(response, id);
    }

    @GetMapping("/pending")
    public List<WorkspaceMembershipDto> pending() {
        return workspaceService.pendingMemberships(authService.getCurrentUser().getId());
    }

    @PostMapping("/{id}/accept")
    public WorkspaceMembershipDto accept(@PathVariable int id, HttpServletResponse response) {
        int userId = authService.getCurrentUser().getId();
        WorkspaceMembershipDto membership = workspaceService.approveMembership(id, userId);
        workspaceService.rememberActive(userId, id);
        workspaceCookie.set(response, id);
        return membership;
    }

    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable int id) {
        workspaceService.declineMembership(id, authService.getCurrentUser().getId());
    }

    @PostMapping("/{id}/leave")
    public WorkspaceSelectionDto leave(@PathVariable int id, HttpServletResponse response) {
        int userId = authService.getCurrentUser().getId();
        Integer nextWorkspaceId = workspaceService.leaveWorkspaceAndSelectNext(id, userId);
        if (nextWorkspaceId == null) {
            workspaceCookie.clear(response);
        } else {
            workspaceCookie.set(response, nextWorkspaceId);
        }
        return new WorkspaceSelectionDto(nextWorkspaceId);
    }

    @PostMapping("/{id}/invites")
    public InviteResultDto invite(@PathVariable int id, @Valid @RequestBody CreateInviteRequest request) {
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

    @PostMapping("/{id}/invite-links")
    public InviteLinkDto createInviteLink(@PathVariable int id, @Valid @RequestBody CreateInviteLinkRequest request) {
        return inviteLinkService.createLink(id, authService.getCurrentUser(),
                request.getRole(), request.getExpiresInDays(), request.getMaxUses());
    }

    @GetMapping("/{id}/invite-links")
    public List<InviteLinkDto> inviteLinks(@PathVariable int id) {
        return inviteLinkService.listLinks(id, authService.getCurrentUser().getId());
    }

    @DeleteMapping("/{id}/invite-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInviteLink(@PathVariable int id, @PathVariable int linkId) {
        inviteLinkService.revokeLink(id, linkId, authService.getCurrentUser().getId());
    }

    @GetMapping("/{id}/allowed-domains")
    public List<String> allowedDomains(@PathVariable int id) {
        return allowedDomainService.listDomains(id, authService.getCurrentUser().getId());
    }

    @PostMapping("/{id}/allowed-domains")
    public List<String> addAllowedDomain(@PathVariable int id, @Valid @RequestBody AddAllowedDomainRequest request) {
        return allowedDomainService.addDomain(id, authService.getCurrentUser().getId(), request.getDomain());
    }

    @DeleteMapping("/{id}/allowed-domains")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAllowedDomain(@PathVariable int id, @RequestParam String domain) {
        allowedDomainService.removeDomain(id, authService.getCurrentUser().getId(), domain);
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
        int actorId = authService.getCurrentUser().getId();
        if (request.getRoleId() != null) {
            return workspaceService.assignCustomRole(id, actorId, userId, request.getRoleId());
        }
        return workspaceService.changeMemberRole(id, actorId, userId, request.getRole());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable int id, @PathVariable int userId) {
        workspaceService.removeMember(id, authService.getCurrentUser().getId(), userId);
    }
}
