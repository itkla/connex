package ooo.klae.connex.backend.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InviteLinkPreviewDto;
import ooo.klae.connex.backend.dto.InviteFlowAcceptRequest;
import ooo.klae.connex.backend.dto.OneTimeLinkExchangeRequest;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.InviteLinkService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.ResolvedFlow;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

/**
 * Token-free invite-link endpoints used after the fragment bearer has been exchanged for a
 * purpose-bound browser flow. Preview and redemption require that flow and an authenticated user;
 * successful redemption joins the workspace and pins it as active.
 */
@RestController
@RequestMapping("/api/invite-links")
@RequiredArgsConstructor
public class InviteLinkController {
    private final InviteLinkService inviteLinkService;
    private final AuthService authService;
    private final WorkspaceService workspaceService;
    private final WorkspaceCookie workspaceCookie;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final OneTimeLinkFlowCookie oneTimeLinkFlowCookie;

    @PostMapping("/exchange")
    public void exchange(
            @Valid @RequestBody OneTimeLinkExchangeRequest dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        String tokenHash = inviteLinkService.exchangeToken(dto.getToken());
        IssuedGrant grant = oneTimeLinkFlowService.issue(
            request, Purpose.WORKSPACE_INVITE_LINK, tokenHash);
        oneTimeLinkFlowCookie.set(
            response, Purpose.WORKSPACE_INVITE_LINK, grant.value(), grant.lifetime());
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", "/invite-link");
    }

    @GetMapping
    public InviteLinkPreviewDto preview(
            @CookieValue(
                name = OneTimeLinkFlowCookie.WORKSPACE_INVITE_LINK,
                required = false) String grant,
            HttpServletRequest request) {
        ResolvedFlow flow = oneTimeLinkFlowService.requireFlow(
            request, Purpose.WORKSPACE_INVITE_LINK, grant);
        InviteLinkPreviewDto preview = inviteLinkService.previewLinkByHash(flow.sourceTokenHash());
        preview.setFlowId(flow.flowId());
        return preview;
    }

    @PostMapping("/accept")
    public WorkspaceMembershipDto accept(
            @Valid @RequestBody InviteFlowAcceptRequest dto,
            @CookieValue(
                name = OneTimeLinkFlowCookie.WORKSPACE_INVITE_LINK,
                required = false) String grant,
            HttpServletRequest request,
            HttpServletResponse response) {
        User user = authService.getCurrentUser();
        String tokenHash = oneTimeLinkFlowService.requireBound(
            request, Purpose.WORKSPACE_INVITE_LINK, grant, dto.getFlowId());
        WorkspaceMembershipDto membership = inviteLinkService.redeemLinkByHash(tokenHash, user);
        workspaceService.rememberActive(user.getId(), membership.getId());
        workspaceCookie.set(response, membership.getId());
        oneTimeLinkFlowService.complete(request, Purpose.WORKSPACE_INVITE_LINK, grant);
        oneTimeLinkFlowCookie.clear(response, Purpose.WORKSPACE_INVITE_LINK);
        return membership;
    }
}
