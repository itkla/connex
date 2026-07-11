package ooo.klae.connex.backend.services;

import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Runs automation work off the request thread under an explicit principal and workspace: it installs
 * a {@link SecurityContext} for the principal and a {@link TenantContext} for the workspace so the
 * existing tenant- and RBAC-enforcing services apply unchanged, then tears both down in a finally.
 * Used by the rule engine to execute actions as the run-as member or the system actor.
 */
@Component
@RequiredArgsConstructor
public class AutomationExecutor {

    private final TenantContext tenantContext;
    private final WorkspaceService workspaceService;
    private final AutomationScope automationScope;
    private final TenantCatalogResolver tenantCatalogResolver;

    /**
     * Runs {@code work} with {@code principal} installed in the security context and {@code workspaceId}
     * in the tenant context, and the thread marked as executing automation (so its mutations do not
     * re-trigger rules), restoring all three afterward.
     */
    public <T> T runAs(int workspaceId, User principal, String role, Supplier<T> work) {
        SecurityContext previousSecurity = SecurityContextHolder.getContext();
        boolean hadTenant = tenantContext.isResolved();
        Integer previousWorkspace = hadTenant ? tenantContext.getWorkspaceId() : null;
        Integer previousUser = hadTenant ? tenantContext.getUserId() : null;
        String previousRole = hadTenant ? tenantContext.getRole() : null;
        Integer previousOrg = hadTenant ? tenantContext.getOrgId() : null;
        String previousCatalog = hadTenant ? tenantContext.getCatalog() : null;

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        int orgId = workspaceService.getOrgId(workspaceId);
        tenantContext.set(workspaceId, orgId, principal.getId(), role, tenantCatalogResolver.resolveCatalog(orgId));
        boolean previousScope = automationScope.enter();
        try {
            return work.get();
        } finally {
            automationScope.restore(previousScope);
            if (hadTenant) {
                tenantContext.set(previousWorkspace, previousOrg, previousUser, previousRole, previousCatalog);
            } else {
                tenantContext.clear();
            }
            SecurityContextHolder.setContext(previousSecurity);
        }
    }
}
