package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.LogoutAuditClaimMapper;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;

/**
 * Appends one logout audit row per servlet-session digest across requests and replicas. The unique
 * database claim and strict audit append share one transaction, including the audit chain's nested
 * savepoint, so an append failure rolls the claim back. The caller deliberately handles any
 * failure and lets Spring Security invalidate the session; audit availability never controls
 * whether logout succeeds.
 */
@Service
@RequiredArgsConstructor
public class LogoutAuditService {

    private final LogoutAuditClaimMapper logoutAuditClaimMapper;
    private final AuditService auditService;
    private final WorkspaceRequestResolver workspaceRequestResolver;
    private final WorkspaceService workspaceService;

    /**
     * Claims and records one logical logout while actor and request context are still available.
     * @param request authenticated logout request
     * @param user authenticated actor
     * @param sessionHash SHA-256 digest of the live servlet-session identifier
     */
    @Transactional
    public void record(HttpServletRequest request, User user, String sessionHash) {
        Integer workspaceId = resolveAccessibleWorkspace(request, user.getId());
        Integer orgId = workspaceId == null ? null : workspaceService.getOrgId(workspaceId);
        if (logoutAuditClaimMapper.claim(sessionHash) != 1) {
            return;
        }
        auditService.recordStrictScoped(
            "auth.logout",
            "user",
            user.getId(),
            workspaceId,
            orgId,
            user.getDisplayName(),
            user.getDisplayName() + " logged out",
            null);
    }

    private Integer resolveAccessibleWorkspace(HttpServletRequest request, int userId) {
        Integer candidate = workspaceRequestResolver.resolve(request, userId);
        if (candidate != null && workspaceService.getRole(candidate, userId) != null) {
            return candidate;
        }
        return workspaceService.defaultWorkspaceIdFor(userId);
    }
}
