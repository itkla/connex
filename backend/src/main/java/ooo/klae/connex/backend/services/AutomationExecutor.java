package ooo.klae.connex.backend.services;

import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Runs automation work off the request thread under an explicit principal and workspace: it installs
 * a {@link SecurityContext} for the principal and a {@link TenantContext} for the workspace so the
 * existing tenant- and RBAC-enforcing services apply unchanged, then tears both down in a finally.
 * The work span also pins the target workspace's catalog explicitly, so a surrounding
 * {@code TenantWorkScope} override (e.g. the rule-trigger listener's) can never mask a
 * cross-workspace {@code runAs} onto the wrong catalog.
 * Used by the rule engine to execute actions as the run-as member or the system actor, and by
 * scheduled system observations through {@link #runAsObserver}.
 */
@Component
@RequiredArgsConstructor
public class AutomationExecutor {

    private final TenantContext tenantContext;
    private final AutomationScope automationScope;
    private final TenantWorkScope tenantWorkScope;

    /**
     * Runs {@code work} with {@code principal} installed in the security context and {@code workspaceId}
     * in the tenant context, and the thread marked as executing automation (so its mutations do not
     * re-trigger rules), restoring all three afterward.
     */
    public <T> T runAs(int workspaceId, User principal, String role, Supplier<T> work) {
        return tenantWorkScope.withWorkspacePlacement(workspaceId,
            (orgId, catalog) -> runWithPinnedPlacement(
                workspaceId, orgId, catalog, principal, role, work, true));
    }

    /**
     * Runs {@code work} exactly like {@link #runAs}, except that the thread is <em>not</em> marked as
     * executing automation, so mutations the work makes do publish rule triggers.
     *
     * <p>This is for scheduled system observations — a first-response SLA breach, for instance —
     * which are events a workspace's own rules are entitled to escalate, not side effects of a rule
     * action. Never call it from inside a rule action: the automation marker is the only thing
     * standing between a rule's mutations and that rule triggering itself again.
     *
     * @param <T> work result type
     * @param workspaceId workspace to install
     * @param principal principal to run as
     * @param role role to install in the tenant context
     * @param work observation to run
     * @return whatever {@code work} returned
     */
    public <T> T runAsObserver(int workspaceId, User principal, String role, Supplier<T> work) {
        return tenantWorkScope.withWorkspacePlacement(workspaceId,
            (orgId, catalog) -> runWithPinnedPlacement(
                workspaceId, orgId, catalog, principal, role, work, false));
    }

    private <T> T runWithPinnedPlacement(int workspaceId, int orgId, String catalog,
            User principal, String role, Supplier<T> work, boolean automation) {
        SecurityContext previousSecurity = SecurityContextHolder.getContext();
        boolean hadTenant = tenantContext.isResolved();
        Integer previousWorkspace = hadTenant ? tenantContext.getWorkspaceId() : null;
        Integer previousUser = hadTenant ? tenantContext.getUserId() : null;
        String previousRole = hadTenant ? tenantContext.getRole() : null;
        Integer previousOrg = hadTenant ? tenantContext.getOrgId() : null;
        String previousCatalog = hadTenant ? tenantContext.getScopeCatalog() : null;

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        tenantContext.set(workspaceId, orgId, principal.getId(), role, catalog);
        boolean previousScope = automation ? automationScope.enter() : automationScope.isActive();
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
