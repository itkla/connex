package ooo.klae.connex.backend.tenant;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Catalog scoping for work that runs without a request-resolved
 * {@link TenantContext} (#485 — the whole-operation-routing flag-enable
 * blocker). Schedulers and async listeners carry an explicit workspace id but
 * no principal, so before this primitive their queries always ran on the
 * default catalog — silently wrong for a dedicated-placement org once
 * {@code catalog-per-placement} is enabled. {@link #inWorkspace} resolves the
 * workspace's placement fail-closed and pins its catalog for the span.
 * Same-workspace nested spans reuse that immutable operation snapshot, while
 * a different workspace resolves independently so nested work cannot jump
 * catalogs during a placement change or inherit the caller's placement;
 * {@link #unrouted} forces the default catalog for control-plane reads (most
 * importantly placement resolution itself, which must never run on a tenant
 * catalog — the nested-{@code runAs} cache-poisoning fix).
 */
@Component
@RequiredArgsConstructor
public class TenantWorkScope {

    private static final class WorkspaceRoutes {
        private final Map<Integer, WorkspaceRoute> routes = new HashMap<>();
        private int depth;
    }

    private record WorkspaceRoute(int orgId, String catalog) {}

    private static final ThreadLocal<WorkspaceRoutes> CURRENT_WORKSPACE_ROUTES = new ThreadLocal<>();

    private final TenantContext tenantContext;
    private final TenantCatalogResolver tenantCatalogResolver;
    private final WorkspaceMapper workspaceMapper;

    /**
     * Runs control-plane work on the default catalog regardless of any routed
     * scope or override active on the thread.
     *
     * @param <T> the work's result type
     * @param work the control-plane work
     * @return the work's result
     */
    public <T> T unrouted(Supplier<T> work) {
        return runWithOverride(Optional.empty(), work);
    }

    /**
     * Resolves the workspace's org placement (fail-closed, on the default
     * catalog) and pins the resulting catalog for the duration of {@code work}.
     * Installs no identity scope — mappers keep receiving their explicit
     * {@code workspaceId} parameters exactly as before; only connection
     * routing changes. Callers iterating many workspaces should catch
     * {@code ServiceUnavailableException} per workspace: an unservable
     * placement skips that workspace rather than aborting the sweep, matching
     * the request path's 503.
     *
     * @param <T> the work's result type
     * @param workspaceId the workspace whose placement governs routing
     * @param work the workspace-scoped work
     * @return the work's result
     * @throws ooo.klae.connex.backend.exceptions.ServiceUnavailableException
     *     when the workspace's org placement cannot be served safely
     * @throws IllegalStateException when the workspace does not exist
     */
    public <T> T inWorkspace(int workspaceId, Supplier<T> work) {
        return runInWorkspace(workspaceId, route -> work.get());
    }

    /**
     * Runs work under the workspace's immutable operation placement and passes
     * the matching organization and catalog to an identity-scope installer.
     * Same-workspace nested calls receive the original pair without another
     * control-plane query.
     *
     * @param <T> the work's result type
     * @param workspaceId the workspace whose placement governs routing
     * @param work the work receiving the pinned organization and catalog
     * @return the work's result
     */
    public <T> T withWorkspacePlacement(int workspaceId, BiFunction<Integer, String, T> work) {
        return runInWorkspace(workspaceId, route -> work.apply(route.orgId(), route.catalog()));
    }

    private <T> T runInWorkspace(int workspaceId, Function<WorkspaceRoute, T> work) {
        WorkspaceRoutes routes = CURRENT_WORKSPACE_ROUTES.get();
        boolean root = routes == null;
        if (root) {
            routes = new WorkspaceRoutes();
            if (tenantContext.isResolved()) {
                routes.routes.put(tenantContext.getWorkspaceId(),
                    new WorkspaceRoute(tenantContext.getOrgId(), tenantContext.getScopeCatalog()));
            }
            CURRENT_WORKSPACE_ROUTES.set(routes);
        }
        try {
            WorkspaceRoute route = routeFor(routes, workspaceId);
            routes.depth++;
            try {
                return runWithOverride(Optional.ofNullable(route.catalog()), () -> work.apply(route));
            } finally {
                routes.depth--;
            }
        } finally {
            if (root && routes.depth == 0) {
                CURRENT_WORKSPACE_ROUTES.remove();
            }
        }
    }

    /**
     * {@link #inWorkspace(int, Supplier)} for void work.
     *
     * @param workspaceId the workspace whose placement governs routing
     * @param work the workspace-scoped work
     */
    public void inWorkspace(int workspaceId, Runnable work) {
        inWorkspace(workspaceId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Pins an explicit catalog ({@code null} = the default catalog) for
     * catalog-fan-out enumeration, where the caller iterates every active
     * catalog before it knows which workspaces live there.
     *
     * @param <T> the work's result type
     * @param catalog the catalog to pin, or {@code null} for the default
     * @param work the work to run against that catalog
     * @return the work's result
     */
    public <T> T withCatalog(String catalog, Supplier<T> work) {
        return runWithOverride(Optional.ofNullable(catalog), work);
    }

    private WorkspaceRoute routeFor(WorkspaceRoutes routes, int workspaceId) {
        WorkspaceRoute existing = routes.routes.get(workspaceId);
        if (existing != null) {
            return existing;
        }
        WorkspaceRoute resolved = resolveRouteForWorkspace(workspaceId);
        routes.routes.put(workspaceId, resolved);
        return resolved;
    }

    private <T> T runWithOverride(Optional<String> override, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !Objects.equals(override.orElse(null), tenantContext.getCatalog())) {
            throw new IllegalStateException(
                "Catalog scope cannot CHANGE inside an active transaction: the transaction-bound "
                    + "connection keeps its original catalog, so the new pin would silently not apply. "
                    + "Re-pinning the same catalog (e.g. runAs within an already-routed span) is allowed");
        }
        Optional<String> previous = tenantContext.swapCatalogOverride(override);
        try {
            return work.get();
        } finally {
            tenantContext.swapCatalogOverride(previous);
        }
    }

    private WorkspaceRoute resolveRouteForWorkspace(int workspaceId) {
        return unrouted(() -> {
            Integer orgId = workspaceMapper.getOrgId(workspaceId);
            if (orgId == null) {
                throw new IllegalStateException("Workspace " + workspaceId + " does not exist");
            }
            return new WorkspaceRoute(orgId, tenantCatalogResolver.resolveCatalog(orgId));
        });
    }
}
