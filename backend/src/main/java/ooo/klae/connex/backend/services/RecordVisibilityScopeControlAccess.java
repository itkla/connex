package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;

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
 * record-visibility ceilings with exact parity (#485, program decision 1). Delegation to
 * {@link ShareWorkspaceControlAccess} retains its prepared routed snapshot, lazy single-database lookup,
 * and tenant-context checks without duplicating that boundary.
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
     * Shares this operation's first needed scope read across read-only or independently handled work.
     * Empty and skipped paths perform no lookup. An ordinary lookup failure is retained for the
     * operation, so each item's existing handler observes it without retrying the control database.
     * Callers that take authorization locks must use an eager boundary before their first lock.
     * Routed reads always validate the prepared context and never use this single-database memo.
     */
    public <T> T withDeferredWorkspaceScope(int workspaceId, Supplier<T> operation) {
        OperationScope previous = operationScope.get();
        if (previous != null && previous.workspaceId == workspaceId) {
            return operation.get();
        }
        return runWithinScope(workspaceId, null, operation);
    }

    /** Reuses a caller's trusted snapshot across nested work and restores any enclosing operation. */
    public <T> T withWorkspaceScope(int workspaceId, RecordVisibilityScope scope, Supplier<T> operation) {
        Objects.requireNonNull(scope, "scope");
        requireScopeIdentity(workspaceId, scope);
        if (shareWorkspaceControlAccess.isCatalogPerPlacement()
                && !scope.equals(loadScope(workspaceId))) {
            throw new TenantScopeSnapshotException("Operation scope does not match the prepared tenant context");
        }
        return runWithinScope(workspaceId, scope, operation);
    }

    private <T> T runWithinScope(int workspaceId, RecordVisibilityScope scope, Supplier<T> operation) {
        if (scope != null) {
            requireScopeIdentity(workspaceId, scope);
        }
        OperationScope previous = operationScope.get();
        operationScope.set(new OperationScope(workspaceId, tenantContext.getCatalog(), tenantContext.isResolved(), tenantContext.getOrgId(), scope));
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
     * Routed operations serve the immutable payload prepared before their first tenant transaction.
     * A missing or mismatched payload inside a routed transaction fails before any control read.
     * In single-database mode only, an actual transaction with synchronization reuses one scope per
     * workspace. The memo is suspended with that transaction and discarded on commit or rollback;
     * outside a transaction or explicit operation boundary, every call reads a fresh snapshot. Catalog-per-placement mode bypasses
     * the memo entirely: every call reaches the boundary so the prepared snapshot supplied by
     * #2165 is validated against the current workspace, organization, and catalog.
     *
     * @param workspaceId workspace anchoring the organization
     * @return the organization's workspace IDs, including the anchor
     * @throws IllegalStateException when the anchor is invalid or the snapshot disagrees with
     *     the resolved tenant context
     */
    public RecordVisibilityScope getForWorkspace(int workspaceId) {
        if (shareWorkspaceControlAccess.isCatalogPerPlacement()) {
            return loadScope(workspaceId);
        }
        OperationScope operation = operationScope.get();
        if (operation != null && operation.workspaceId == workspaceId) {
            if (operation.resolved && !tenantContext.isResolved()
                    || !Objects.equals(operation.catalog, tenantContext.getCatalog())
                    || tenantContext.isResolved() && (tenantContext.getWorkspaceId().intValue() != workspaceId
                        || operation.orgId != null && !Objects.equals(operation.orgId, tenantContext.getOrgId()))) {
                throw new TenantScopeSnapshotException("Operation scope does not match the resolved tenant catalog");
            }
            if (operation.scope != null) {
                requireScopeIdentity(workspaceId, operation.scope);
                return operation.scope;
            }
            if (operation.failure != null) {
                throw operation.failure;
            }
            try {
                RecordVisibilityScope scope = transactionScope(workspaceId);
                requireScopeIdentity(workspaceId, scope);
                operation.scope = scope;
                return scope;
            } catch (TenantScopeSnapshotException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                operation.failure = exception;
                throw exception;
            }
        }
        return transactionScope(workspaceId);
    }

    private RecordVisibilityScope transactionScope(int workspaceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return loadScope(workspaceId);
        }
        TransactionScopeKey key = new TransactionScopeKey(this, workspaceId);
        if (TransactionSynchronizationManager.getResource(key) instanceof RecordVisibilityScope scope) {
            if (tenantContext.isResolved()
                    && (tenantContext.getWorkspaceId().intValue() != workspaceId
                        || tenantContext.getOrgId().intValue() != scope.orgId())) {
                throw new TenantScopeSnapshotException("Workspace scope does not match the resolved tenant context");
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
     * In single-database mode this bypasses, and never replaces, the transaction memo. The
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
            throw new TenantScopeSnapshotException("Workspace scope does not match the resolved tenant context");
        }
    }

    /** A synchronous operation owns this snapshot across its independent transaction boundaries. */
    private static final class OperationScope {
        private final int workspaceId;
        private final String catalog;
        private final boolean resolved;
        private final Integer orgId;
        private RecordVisibilityScope scope;
        private RuntimeException failure;

        private OperationScope(int workspaceId, String catalog, boolean resolved, Integer orgId, RecordVisibilityScope scope) {
            this.workspaceId = workspaceId;
            this.catalog = catalog;
            this.resolved = resolved;
            this.orgId = orgId;
            this.scope = scope;
        }
    }

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
