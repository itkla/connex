package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Resolves the unfiltered organization workspace set preserving legacy {@code JOIN workspace}
 * record-visibility ceilings with exact parity (#485, program decision 1). Both modes consume
 * context-prepared data without SQL; {@link ShareWorkspaceControlAccess} supplies the lazy fallback
 * without changing ordinary single-database share-grant or listing freshness.
 *
 * <p>This deliberately differs from the active-only {@link OrganizationWorkspaceScopeControlAccess}
 * used by Identity, PersonEdge, and assistant ceilings. Any lifecycle tightening is a separate,
 * explicit decision; callers must not substitute one scope for the other.
 */
@Component
@RequiredArgsConstructor
public class RecordVisibilityScopeControlAccess {
    private final ShareWorkspaceControlAccess shareWorkspaceControlAccess;
    private final TenantContext tenantContext;
    private final ThreadLocal<OperationScope> operationScope = new ThreadLocal<>();

    /**
     * Shares one snapshot across an operation's independent transactions without changing commits.
     * The scope is confined to this synchronous call and restored on every exit. Routed lookups
     * still validate the context's prepared payload on every read; this memo is single-database only.
     * A catalog-only operation may install an automation identity later, provided its workspace,
     * organization and catalog still match the snapshot. A resolved entry identity must remain resolved.
     */
    public <T> T withWorkspaceScope(int workspaceId, Supplier<T> operation) {
        return runWithinScope(workspaceId, getForWorkspace(workspaceId), operation);
    }

    /**
     * Reuses a caller's trusted snapshot across nested work and restores any enclosing operation.
     * In single-database mode it takes precedence over prepared data, keeping nested reads aligned
     * with the scope explicitly passed to batch statements. Routed scopes must equal the exact
     * prepared payload and cannot substitute for a missing payload.
     */
    public <T> T withWorkspaceScope(int workspaceId, RecordVisibilityScope scope, Supplier<T> operation) {
        Objects.requireNonNull(scope, "scope");
        requireScopeIdentity(workspaceId, scope);
        if (shareWorkspaceControlAccess.isCatalogPerPlacement()
                && !scope.equals(getFreshForWorkspace(workspaceId))) {
            throw new IllegalStateException("Operation scope does not match the prepared tenant context");
        }
        return runWithinScope(workspaceId, scope, operation);
    }

    private <T> T runWithinScope(int workspaceId, RecordVisibilityScope scope, Supplier<T> operation) {
        requireScopeIdentity(workspaceId, scope);
        OperationScope previous = operationScope.get();
        operationScope.set(new OperationScope(workspaceId, tenantContext.getCatalog(), tenantContext.isResolved(), scope));
        try {
            return operation.get();
        } finally {
            if (previous == null) {
                operationScope.remove();
            } else {
                operationScope.set(previous);
            }
        }
    }

    /**
     * Resolves one immutable, unfiltered record-visibility ceiling for an operation.
     * Both modes serve a payload matching the current workspace, organization and effective catalog.
     * Single-database precedence is explicit operation scope, prepared payload, transaction memo,
     * then lazy lookup. The memo belongs only to an actual synchronized transaction, is suspended
     * with it and is discarded on completion. Without any of these scopes, each call reads afresh.
     * A transaction-first single-database entry retains that lazy behavior; at REPEATABLE READ its
     * first consistent scope read can establish a pre-lock read view (see {@code docs/backend/LOCKING.md}).
     * Routed reads bypass both memos and refuse a missing payload inside a transaction before SQL.
     *
     * @param workspaceId workspace anchoring the organization
     * @return the organization's workspace IDs, including the anchor
     * @throws IllegalStateException when the anchor is invalid or the snapshot disagrees with
     *     the resolved tenant context
     */
    public RecordVisibilityScope getForWorkspace(int workspaceId) {
        boolean routed = shareWorkspaceControlAccess.isCatalogPerPlacement();
        OperationScope operation = operationScope.get();
        if (!routed && operation != null && operation.workspaceId() == workspaceId) {
            requireScopeIdentity(workspaceId, operation.scope());
            if (operation.resolved() && !tenantContext.isResolved()
                    || !Objects.equals(operation.catalog(), tenantContext.getCatalog())) {
                throw new IllegalStateException("Operation scope does not match the resolved tenant catalog");
            }
            return operation.scope();
        }
        var prepared = tenantContext.getWorkspaceScopes();
        if (prepared != null && prepared.workspaceId() == workspaceId) {
            return prepared.requireSharing().recordVisibilityScope();
        }
        if (routed) {
            return loadScope(workspaceId);
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return loadScope(workspaceId);
        }
        TransactionScopeKey key = new TransactionScopeKey(this, workspaceId);
        if (TransactionSynchronizationManager.getResource(key) instanceof RecordVisibilityScope scope) {
            if (tenantContext.isResolved()
                    && (tenantContext.getWorkspaceId().intValue() != workspaceId
                        || tenantContext.getOrgId().intValue() != scope.orgId())) {
                throw new IllegalStateException("Workspace scope does not match the resolved tenant context");
            }
            return scope;
        }
        RecordVisibilityScope scope = loadScope(workspaceId);
        TransactionSynchronizationManager.bindResource(key, scope);
        TransactionSynchronizationManager.registerSynchronization(new TransactionScopeMemo(key, scope));
        return scope;
    }

    /**
     * Resolves the destructive cache-purge ceiling immediately before its tenant delete.
     * In single-database mode this bypasses, and never replaces, the explicit operation scope,
     * prepared payload and transaction memo. The
     * default-catalog caller retains its existing connection and locks: under READ_COMMITTED,
     * this read observes workspace creation committed since an earlier visibility lookup.
     * This is the cache purge's explicit exception to scope-before-locks ordering.
     *
     * <p>Routed calls require the exact prepared #2165 snapshot and never query, suspend, or
     * borrow a connection, even outside a transaction. Its operation-level staleness for
     * destructive consumers remains a routing flag-enable prerequisite in #2172.
     *
     * @param workspaceId workspace anchoring the organization
     * @return the immutable unfiltered organization workspace set
     * @throws IllegalStateException when the anchor or prepared tenant scope is invalid
     */
    public RecordVisibilityScope getFreshForWorkspace(int workspaceId) {
        if (shareWorkspaceControlAccess.isCatalogPerPlacement()) {
            var prepared = tenantContext.getWorkspaceScopes();
            if (prepared == null || prepared.workspaceId() != workspaceId) {
                throw new TenantScopeSnapshotMissingException("workspace " + workspaceId);
            }
            return prepared.requireSharing().recordVisibilityScope();
        }
        return shareWorkspaceControlAccess.getFreshForWorkspace(workspaceId).recordVisibilityScope();
    }

    private RecordVisibilityScope loadScope(int workspaceId) {
        var snapshot = shareWorkspaceControlAccess.getForWorkspace(workspaceId);
        return snapshot.recordVisibilityScope();
    }

    private void requireScopeIdentity(int workspaceId, RecordVisibilityScope scope) {
        if (!scope.workspaceIds().contains(workspaceId)
                || tenantContext.isResolved()
                    && (tenantContext.getWorkspaceId().intValue() != workspaceId
                        || tenantContext.getOrgId().intValue() != scope.orgId())) {
            throw new IllegalStateException("Workspace scope does not match the resolved tenant context");
        }
    }

    /** A synchronous operation owns this snapshot across its independent transaction boundaries. */
    private record OperationScope(int workspaceId, String catalog, boolean resolved, RecordVisibilityScope scope) { }

    /** Keeps each boundary instance's workspace snapshots distinct within the current transaction. */
    private record TransactionScopeKey(RecordVisibilityScopeControlAccess boundary, int workspaceId) { }

    /**
     * Prevents suspended or completed transactions from lending their snapshots to other work.
     * Runs before deferred completion work can start an independent transaction, because Spring
     * clears the synchronization list before invoking completion callbacks.
     */
    private record TransactionScopeMemo(TransactionScopeKey key, RecordVisibilityScope scope)
            implements TransactionSynchronization {
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResource(key);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(key, scope);
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResource(key);
        }
    }

    /** An unfiltered organization workspace set, distinct from active-only read scopes. */
    public record RecordVisibilityScope(int orgId, List<Integer> workspaceIds) {
        public RecordVisibilityScope {
            workspaceIds = List.copyOf(workspaceIds);
        }

        /** Returns the trusted allowlist bound by tenant SQL through {@code JSON_TABLE}. */
        public String workspaceIdsJson() {
            return workspaceIds.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        }
    }
}
