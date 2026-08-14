package ooo.klae.connex.backend.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;

/**
 * Records a user-initiated logout before Spring Security invalidates the HTTP session and clears
 * the security context. This ordering lets {@link AuditService} capture the authenticated actor
 * and its one-way session digest. The handler intentionally swallows failures so the following
 * security logout handlers always destroy the session; a broken audit sink must never keep a user
 * signed in. Anonymous, expired, already-invalidated, and repeated logout requests have neither an
 * authenticated user nor a live session and therefore do not create a misleading logout event.
 * Server-side bulk session revocation is a different security operation and does not pass through
 * this user-initiated logout handler.
 */
@Component
@RequiredArgsConstructor
public class LogoutAuditHandler implements LogoutHandler {

    private static final String RECORDED_ATTRIBUTE = LogoutAuditHandler.class.getName() + ".RECORDED";

    private final AuditService auditService;
    private final WorkspaceRequestResolver workspaceRequestResolver;
    private final WorkspaceService workspaceService;

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null || authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)) {
            return;
        }
        synchronized (session) {
            if (Boolean.TRUE.equals(session.getAttribute(RECORDED_ATTRIBUTE))) {
                return;
            }
            session.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);
        }
        try {
            Integer workspaceId = resolveAccessibleWorkspace(request, user.getId());
            Integer orgId = workspaceId == null ? null : workspaceService.getOrgId(workspaceId);
            auditService.recordScoped(
                    "auth.logout",
                    "user",
                    user.getId(),
                    workspaceId,
                    orgId,
                    user.getDisplayName(),
                    user.getDisplayName() + " logged out",
                    null);
        } catch (RuntimeException exception) {
            return;
        }
    }

    private Integer resolveAccessibleWorkspace(HttpServletRequest request, int userId) {
        Integer candidate = workspaceRequestResolver.resolve(request, userId);
        if (candidate != null && workspaceService.getRole(candidate, userId) != null) {
            return candidate;
        }
        return workspaceService.defaultWorkspaceIdFor(userId);
    }
}
