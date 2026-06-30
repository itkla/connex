package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.AllowedDomainMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * The per-workspace email-domain allowlist that gates the broad self-serve join channels (invite
 * links, and the future on-prem domain-signup mode). Reads/writes are owner/admin-gated
 * ({@code WORKSPACE_SETTINGS}); {@link #isJoinAllowed} is the reusable gate, returning true when the
 * allowlist is empty so existing workspaces stay unrestricted. Explicit email invites are not gated.
 */
@Service
@RequiredArgsConstructor
public class AllowedDomainService {

    private final AllowedDomainMapper allowedDomainMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    public List<String> listDomains(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        return allowedDomainMapper.findByWorkspace(workspaceId);
    }

    public List<String> addDomain(int workspaceId, int actorId, String domainRaw) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        String domain = normalizeDomain(domainRaw);
        allowedDomainMapper.add(workspaceId, domain);
        auditService.record("workspace.allowed_domain.add", "workspace", workspaceId, domain,
                "Allowed domain " + domain, null);
        return allowedDomainMapper.findByWorkspace(workspaceId);
    }

    public void removeDomain(int workspaceId, int actorId, String domainRaw) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        String domain = normalizeDomain(domainRaw);
        allowedDomainMapper.remove(workspaceId, domain);
        auditService.record("workspace.allowed_domain.remove", "workspace", workspaceId, domain,
                "Removed allowed domain " + domain, null);
    }

    /**
     * Whether {@code email}'s domain may join {@code workspaceId} via a self-serve channel. True
     * when the workspace has no allowlist (unrestricted — the non-breaking default), otherwise the
     * email's domain must be on the list.
     */
    public boolean isJoinAllowed(int workspaceId, String email) {
        if (allowedDomainMapper.countByWorkspace(workspaceId) == 0) {
            return true;
        }
        return allowedDomainMapper.isAllowed(workspaceId, domainOf(email));
    }

    private static String normalizeDomain(String domainRaw) {
        if (domainRaw == null || domainRaw.isBlank()) {
            throw new BadRequestException("Domain is required");
        }
        String domain = domainRaw.trim().toLowerCase();
        if (domain.startsWith("@")) {
            domain = domain.substring(1);
        }
        if (domain.isEmpty() || domain.contains("@") || domain.contains(" ") || !domain.contains(".")) {
            throw new BadRequestException("Enter a valid domain such as example.com");
        }
        return domain;
    }

    private static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at >= 0 ? email.substring(at + 1).trim().toLowerCase() : "";
    }
}
