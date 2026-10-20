package ooo.klae.connex.backend.services;

import java.util.function.Supplier;
import java.util.Objects;

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
     * re-trigger rules), restoring all three afterward. The compatibility overload attributes work
     * as {@code background_task}; domain callers use the overload with an explicit source.
     */
    public <T> T runAs(int workspaceId, User principal, String role, Supplier<T> work) {
        return runAs(workspaceId, principal, role,
            AutomationScope.Origin.task(AutomationScope.Source.BACKGROUND_TASK,
                "system".equals(role) ? "system" : "user"), work);
    }

    /**
     * Runs work under an explicit execution origin, captured by audit entries independently of
     * the principal's identity. Rule and workflow callers must supply their persisted run ids.
     */
    public <T> T runAs(int workspaceId, User principal, String role,
            AutomationScope.Origin origin, Supplier<T> work) {
        Objects.requireNonNull(origin, "origin");
        return tenantWorkScope.withWorkspacePlacement(workspaceId,
            (orgId, catalog) -> runWithPinnedPlacement(
                workspaceId, orgId, catalog, principal, role, work, origin, true));
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
                workspaceId, orgId, catalog, principal, role, work, null, false));
    }

    /**
     * Attributes an observer that also writes domain state, while permitting those mutations to
     * publish rule triggers. Pure observations use the overload without an execution origin.
     */
    public <T> T runAsObserver(int workspaceId, User principal, String role,
            AutomationScope.Origin origin, Supplier<T> work) {
        Objects.requireNonNull(origin, "origin");
        return tenantWorkScope.withWorkspacePlacement(workspaceId,
            (orgId, catalog) -> runWithPinnedPlacement(
                workspaceId, orgId, catalog, principal, role, work, origin, false));
    }

    private <T> T runWithPinnedPlacement(int workspaceId, int orgId, String catalog,
            User principal, String role, Supplier<T> work, AutomationScope.Origin origin,
            boolean suppressTriggers) {
        SecurityContext previousSecurity = SecurityContextHolder.getContext();
        TenantContext.IdentitySnapshot previousIdentity = tenantContext.snapshotIdentity();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        tenantContext.set(workspaceId, orgId, principal.getId(), role, catalog);
        AutomationScope.State previousScope = origin == null
            ? automationScope.snapshot()
            : suppressTriggers ? automationScope.enter(origin) : automationScope.attribute(origin);
        try {
            return work.get();
        } finally {
            automationScope.restore(previousScope);
            tenantContext.restoreIdentity(previousIdentity);
            SecurityContextHolder.setContext(previousSecurity);
        }
    }
}
