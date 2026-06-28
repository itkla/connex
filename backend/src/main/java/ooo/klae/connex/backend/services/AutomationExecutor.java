package ooo.klae.connex.backend.services;

import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
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

    /**
     * Runs {@code work} with {@code principal} installed in the security context and {@code workspaceId}
     * in the tenant context, restoring both afterward.
     */
    public <T> T runAs(int workspaceId, User principal, String role, Supplier<T> work) {
        SecurityContext previousSecurity = SecurityContextHolder.getContext();
        boolean hadTenant = tenantContext.isResolved();
        Integer previousWorkspace = hadTenant ? tenantContext.getWorkspaceId() : null;
        Integer previousUser = hadTenant ? tenantContext.getUserId() : null;
        String previousRole = hadTenant ? tenantContext.getRole() : null;

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        tenantContext.set(workspaceId, principal.getId(), role);
        try {
            return work.get();
        } finally {
            if (hadTenant) {
                tenantContext.set(previousWorkspace, previousUser, previousRole);
            } else {
                tenantContext.clear();
            }
            SecurityContextHolder.setContext(previousSecurity);
        }
    }
}
