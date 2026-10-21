package ooo.klae.connex.backend.tenant;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;

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
public class TenantWorkScope {

    private static final class WorkspaceRoutes {
        private final Map<Integer, WorkspaceRoute> routes = new HashMap<>();
        private final Set<Integer> lifecycleResolved = new HashSet<>();
        private int depth;
    }

    private record WorkspaceRoute(int orgId, String catalog, TenantWorkspaceScopeSnapshot scopes) {
        private WorkspaceRoute(int orgId, String catalog) {
            this(orgId, catalog, null);
        }
    }

    private final class WorkspaceRouteScope implements AutoCloseable {
        private final WorkspaceRoutes routes;
        private final WorkspaceRoute route;
        private final boolean root;
        private final Optional<String> previous;
        private final TenantWorkspaceScopeSnapshot previousScopes;

        private WorkspaceRouteScope(
                WorkspaceRoutes routes, WorkspaceRoute route, boolean root) {
            this.routes = routes;
            this.route = route;
            this.root = root;
            this.previous = beginOverride(Optional.ofNullable(route.catalog()));
            this.previousScopes = tenantContext.swapWorkspaceScopes(route.scopes());
        }

        @Override
        public void close() {
            try {
                tenantContext.swapCatalogOverride(previous);
                tenantContext.swapWorkspaceScopes(previousScopes);
            } finally {
                routes.depth--;
                if (root && routes.depth == 0) {
                    CURRENT_WORKSPACE_ROUTES.remove();
                }
            }
        }
    }

    private static final ThreadLocal<WorkspaceRoutes> CURRENT_WORKSPACE_ROUTES = new ThreadLocal<>();

    private final TenantContext tenantContext;
    private final TenantCatalogResolver tenantCatalogResolver;
    private final WorkspaceMapper workspaceMapper;
    private final Function<Integer, Integer> lifecycleOrgResolver;
    private final TenantWorkspaceScopeControlOperations scopeControlOperations;
    private final BooleanSupplier routingEnabled;

    /** Creates the production scope with ordinary and trusted lifecycle resolvers. */
    @Autowired
    public TenantWorkScope(
            TenantContext tenantContext,
            TenantCatalogResolver tenantCatalogResolver,
            WorkspaceMapper workspaceMapper,
            TenantLifecycleControlMapper tenantLifecycleControlMapper,
            TenantWorkspaceScopeControlOperations scopeControlOperations,
            TenantRoutingProperties routingProperties) {
        this(tenantContext, tenantCatalogResolver, workspaceMapper, tenantLifecycleControlMapper,
            scopeControlOperations, routingProperties::isCatalogPerPlacement);
    }

    /** Creates explicitly routed scope preparation for focused tests. */
    public TenantWorkScope(TenantContext tenantContext, TenantCatalogResolver tenantCatalogResolver,
            WorkspaceMapper workspaceMapper, TenantLifecycleControlMapper lifecycleMapper,
            TenantWorkspaceScopeControlOperations scopeControlOperations) {
        this(tenantContext, tenantCatalogResolver, workspaceMapper, lifecycleMapper,
            scopeControlOperations, tenantCatalogResolver::isCatalogPerPlacement);
    }

    private TenantWorkScope(TenantContext tenantContext, TenantCatalogResolver tenantCatalogResolver,
            WorkspaceMapper workspaceMapper, TenantLifecycleControlMapper tenantLifecycleControlMapper,
            TenantWorkspaceScopeControlOperations scopeControlOperations, BooleanSupplier routingEnabled) {
        this.tenantContext = tenantContext;
        this.tenantCatalogResolver = tenantCatalogResolver;
        this.workspaceMapper = workspaceMapper;
        this.lifecycleOrgResolver =
            tenantLifecycleControlMapper::findWorkspaceOrgIdForLifecycle;
        this.scopeControlOperations = scopeControlOperations;
        this.routingEnabled = routingEnabled;
    }

    /** Creates a scope with explicit lifecycle routing for focused tests. */
    public TenantWorkScope(TenantContext tenantContext, TenantCatalogResolver tenantCatalogResolver,
            WorkspaceMapper workspaceMapper, TenantLifecycleControlMapper lifecycleMapper) {
        this(tenantContext, tenantCatalogResolver, workspaceMapper, lifecycleMapper,
            new TenantWorkspaceScopeControlOperations(workspaceMapper, lifecycleMapper, tenantCatalogResolver), () -> false);
    }

    /** Creates an ordinary-routing scope for focused unit and routing tests. */
    public TenantWorkScope(
            TenantContext tenantContext,
            TenantCatalogResolver tenantCatalogResolver,
            WorkspaceMapper workspaceMapper) {
        this.tenantContext = tenantContext;
        this.tenantCatalogResolver = tenantCatalogResolver;
        this.workspaceMapper = workspaceMapper;
        this.lifecycleOrgResolver = workspaceMapper::getOrgId;
        this.scopeControlOperations = new TenantWorkspaceScopeControlOperations(
            workspaceMapper, tenantCatalogResolver);
        this.routingEnabled = () -> false;
    }

    /** Whether missing prepared scopes must be refused inside tenant transactions. */
    public boolean isCatalogPerPlacement() {
        return routingEnabled.getAsBoolean();
    }

    /**
     * Prepares a validated route before tenant transactions in either mode. A transactional caller
     * may reuse an exact installed payload but never loads a replacement: single-database entries
     * skip preparation and retain lazy boundary reads; routed entries refuse the missing payload.
     */
    public TenantWorkspaceScopeSnapshot prepareWorkspaceScopes(int workspaceId, int orgId, String catalog) {
        return prepareWorkspaceScopes(workspaceId, orgId, catalog, null);
    }

    private TenantWorkspaceScopeSnapshot prepareWorkspaceScopes(
            int workspaceId, int orgId, String catalog, WorkspaceScope knownActiveScope) {
        TenantWorkspaceScopeSnapshot existing = tenantContext.getWorkspaceScopes();
        if (existing != null && existing.matches(workspaceId, orgId, catalog)) {
            return existing;
        }
        requireScopePreparationAllowed("workspace " + workspaceId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }
        return unroutedRead(() -> scopeControlOperations.load(workspaceId, orgId, catalog, knownActiveScope));
    }

    /**
     * Resolves a trusted lifecycle placement and scopes before tenant work. Single-database entries
     * inside an actual transaction skip preparation; routed entries retain their refusal.
     */
    public TenantWorkspaceScopeSnapshot prepareLifecycleWorkspace(int workspaceId, int orgId) {
        requireScopePreparationAllowed("lifecycle workspace " + workspaceId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }
        return unroutedRead(() -> scopeControlOperations.forLifecycleWorkspace(workspaceId, orgId));
    }

    /** Refuses a scope lookup before it can suspend or borrow under a routed transaction. */
    public void requireScopePreparationAllowed(String requestedScope) {
        if (isCatalogPerPlacement() && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new TenantScopeSnapshotMissingException(requestedScope);
        }
    }

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
     * Runs a control-plane read on the default catalog exactly as {@link #unrouted} does, but
     * through its own functional interface (#1815). The read never passes through the
     * {@code Supplier} that mutating control-plane work shares, so a read-only request cannot appear
     * to reach those writes. Use it only for work that changes no state.
     *
     * @param <T> the read's result type
     * @param read the control-plane read
     * @return the read's result
     */
    public <T> T unroutedRead(ControlPlaneRead<T> read) {
        Optional<String> previous = beginOverride(Optional.empty());
        try {
            return read.read();
        } finally {
            tenantContext.swapCatalogOverride(previous);
        }
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
        return runInWorkspace(workspaceId, false, route -> work.get());
    }

    /**
     * Prepares and installs an active workspace's placement before a read opens its transaction.
     * The callback never joins the functional invocation used by mutating workspace work.
     *
     * @param <T> the read's result type
     * @param workspaceId the workspace whose placement governs the read
     * @param read the read, which must not call a Supplier/Runnable-based workspace entry
     * @return the read's result
     */
    public <T> T inWorkspaceRead(int workspaceId, WorkspaceRead<T> read) {
        try (var scope = enterWorkspaceRoute(workspaceId, false)) {
            return read.read();
        }
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
        return runInWorkspace(
            workspaceId,
            false,
            route -> work.apply(route.orgId(), route.catalog()));
    }

    /** Reuses a control-derived active organization scope already read by diagnostics. */
    public <T> T withWorkspacePlacement(int workspaceId, WorkspaceScope activeScope,
            BiFunction<Integer, String, T> work) {
        try (var scope = enterWorkspaceRoute(workspaceId, false, activeScope)) {
            return work.apply(scope.route.orgId(), scope.route.catalog());
        }
    }

    /** Discovers a placement for grouping only, without installing a route or loading scopes. */
    public <T> T readWorkspacePlacement(int workspaceId, BiFunction<Integer, String, T> read) {
        WorkspaceRoute route = resolveRouteForWorkspace(workspaceId);
        return read.apply(route.orgId(), route.catalog());
    }

    /**
     * Installs an explicitly captured route and its payload without rereading a lifecycle root.
     * The payload may record an unavailable anchor; only a subsequent boundary read refuses it.
     * An uncaptured single-database payload is prepared here only outside an actual transaction.
     */
    public <T> T withPreparedWorkspace(int workspaceId, int orgId, String catalog,
            TenantWorkspaceScopeSnapshot scopes, Supplier<T> work) {
        if (scopes != null && !scopes.matches(workspaceId, orgId, catalog)) {
            throw new IllegalArgumentException("Prepared workspace scopes do not match the captured route");
        }
        TenantWorkspaceScopeSnapshot prepared = scopes == null && !isCatalogPerPlacement()
            ? prepareWorkspaceScopes(workspaceId, orgId, catalog) : scopes;
        return withCatalog(catalog, () -> {
            TenantWorkspaceScopeSnapshot previousScopes = tenantContext.swapWorkspaceScopes(prepared);
            try {
                return work.get();
            } finally {
                tenantContext.swapWorkspaceScopes(previousScopes);
            }
        });
    }

    /**
     * Routes trusted cleanup and reconciliation through a workspace whose
     * lifecycle state may already be {@code tearing_down}.
     */
    public <T> T inLifecycleWorkspace(int workspaceId, Supplier<T> work) {
        return runInWorkspace(workspaceId, true, route -> work.get());
    }

    /**
     * Reads trusted lifecycle progress through the same placement rules as
     * {@link #inLifecycleWorkspace(int, Supplier)}, without sharing a callback with mutations.
     *
     * @param <T> the read's result type
     * @param workspaceId the workspace whose lifecycle placement governs the read
     * @param read the workspace read, which must not mutate persistent or shared state or call
     *     a Supplier/Runnable-based {@code TenantWorkScope} entry
     * @return the read's result
     */
    public <T> T inLifecycleWorkspaceRead(int workspaceId, WorkspaceRead<T> read) {
        try (var scope = enterWorkspaceRoute(workspaceId, true)) {
            return read.read();
        }
    }

    /** {@link #inLifecycleWorkspace(int, Supplier)} for void cleanup work. */
    public void inLifecycleWorkspace(int workspaceId, Runnable work) {
        inLifecycleWorkspace(workspaceId, () -> {
            work.run();
            return null;
        });
    }

    private <T> T runInWorkspace(
            int workspaceId,
            boolean lifecycle,
            Function<WorkspaceRoute, T> work) {
        try (var scope = enterWorkspaceRoute(workspaceId, lifecycle)) {
            return work.apply(scope.route);
        }
    }

    private WorkspaceRouteScope enterWorkspaceRoute(int workspaceId, boolean lifecycle) {
        return enterWorkspaceRoute(workspaceId, lifecycle, null);
    }

    private WorkspaceRouteScope enterWorkspaceRoute(
            int workspaceId, boolean lifecycle, WorkspaceScope knownActiveScope) {
        WorkspaceRoutes routes = CURRENT_WORKSPACE_ROUTES.get();
        boolean root = routes == null;
        if (root) {
            routes = new WorkspaceRoutes();
            if (tenantContext.isResolved()) {
                routes.routes.put(tenantContext.getWorkspaceId(),
                    new WorkspaceRoute(tenantContext.getOrgId(), tenantContext.getScopeCatalog(),
                        tenantContext.getWorkspaceScopes()));
            }
            CURRENT_WORKSPACE_ROUTES.set(routes);
        }
        try {
            WorkspaceRoute route = routeFor(routes, workspaceId, lifecycle, knownActiveScope);
            routes.depth++;
            try {
                return new WorkspaceRouteScope(routes, route, root);
            } catch (RuntimeException | Error failure) {
                routes.depth--;
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            if (root && routes.depth == 0) {
                CURRENT_WORKSPACE_ROUTES.remove();
            }
            throw failure;
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

    private WorkspaceRoute routeFor(
            WorkspaceRoutes routes,
            int workspaceId,
            boolean lifecycle,
            WorkspaceScope knownActiveScope) {
        WorkspaceRoute existing = routes.routes.get(workspaceId);
        if (existing != null
                && (lifecycle || !routes.lifecycleResolved.contains(workspaceId))) {
            if (existing.scopes() == null) {
                existing = new WorkspaceRoute(existing.orgId(), existing.catalog(),
                    prepareWorkspaceScopes(workspaceId, existing.orgId(), existing.catalog(), knownActiveScope));
                routes.routes.put(workspaceId, existing);
            }
            return existing;
        }
        WorkspaceRoute resolved;
        if (isCatalogPerPlacement()) {
            requireScopePreparationAllowed("workspace " + workspaceId);
            TenantWorkspaceScopeSnapshot prepared = unroutedRead(() ->
                scopeControlOperations.forWorkspace(workspaceId, lifecycle, knownActiveScope));
            resolved = new WorkspaceRoute(prepared.orgId(), prepared.catalog(), prepared);
        } else {
            WorkspaceRoute route = lifecycle
                ? resolveLifecycleRouteForWorkspace(workspaceId)
                : resolveRouteForWorkspace(workspaceId);
            resolved = new WorkspaceRoute(route.orgId(), route.catalog(),
                prepareWorkspaceScopes(workspaceId, route.orgId(), route.catalog(), knownActiveScope));
        }
        routes.routes.put(workspaceId, resolved);
        if (lifecycle) {
            routes.lifecycleResolved.add(workspaceId);
        } else {
            routes.lifecycleResolved.remove(workspaceId);
        }
        return resolved;
    }

    private <T> T runWithOverride(Optional<String> override, Supplier<T> work) {
        Optional<String> previous = beginOverride(override);
        try {
            return work.get();
        } finally {
            tenantContext.swapCatalogOverride(previous);
        }
    }

    private Optional<String> beginOverride(Optional<String> override) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !Objects.equals(override.orElse(null), tenantContext.getCatalog())) {
            throw new IllegalStateException(
                "Catalog scope cannot CHANGE inside an active transaction: the transaction-bound "
                    + "connection keeps its original catalog, so the new pin would silently not apply. "
                    + "Re-pinning the same catalog (e.g. runAs within an already-routed span) is allowed");
        }
        return tenantContext.swapCatalogOverride(override);
    }

    private WorkspaceRoute resolveRouteForWorkspace(int workspaceId) {
        return unroutedRead(() -> {
            Integer orgId = workspaceMapper.getOrgId(workspaceId);
            if (orgId == null) {
                throw new IllegalStateException("Workspace " + workspaceId + " does not exist");
            }
            return new WorkspaceRoute(orgId, tenantCatalogResolver.resolveCatalog(orgId));
        });
    }

    private WorkspaceRoute resolveLifecycleRouteForWorkspace(int workspaceId) {
        return unroutedRead(() -> {
            Integer orgId = lifecycleOrgResolver.apply(workspaceId);
            if (orgId == null) {
                throw new IllegalStateException("Workspace " + workspaceId + " does not exist");
            }
            return new WorkspaceRoute(orgId, tenantCatalogResolver.resolveCatalog(orgId));
        });
    }
}
