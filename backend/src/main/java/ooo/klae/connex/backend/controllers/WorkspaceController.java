package ooo.klae.connex.backend.controllers;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.CreateWorkspaceRequest;
import ooo.klae.connex.backend.dto.MyWorkspacesDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.services.AuthService;
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
}
