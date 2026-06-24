package ooo.klae.connex.backend.controllers;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InvitePreviewDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.InviteService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

/**
 * Token-addressed invite endpoints used by the accept screen. Preview is open to
 * any authenticated user holding the token; accepting joins the workspace and
 * pins it as active.
 */
@RestController
@RequestMapping("/api/invites")
@RequiredArgsConstructor
public class InviteController {
    private final InviteService inviteService;
    private final AuthService authService;
    private final WorkspaceService workspaceService;

    @GetMapping("/{token}")
    public InvitePreviewDto preview(@PathVariable String token) {
        return inviteService.previewInvite(token);
    }

    @PostMapping("/{token}/accept")
    public WorkspaceMembershipDto accept(@PathVariable String token, HttpServletResponse response) {
        User user = authService.getCurrentUser();
        WorkspaceMembershipDto membership = inviteService.acceptInvite(token, user);
        workspaceService.rememberActive(user.getId(), membership.getId());
        WorkspaceCookie.set(response, membership.getId());
        return membership;
    }
}
