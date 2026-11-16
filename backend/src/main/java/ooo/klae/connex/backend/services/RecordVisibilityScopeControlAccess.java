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
            throw new TenantScopeSnapshotException("Operation scope does not match the prepared tenant context");
        }
        return runWithinScope(workspaceId, scope, operation);
    }

    private <T> T runWithinScope(int workspaceId, RecordVisibilityScope scope, Supplier<T> operation) {
        try (var ignored = enterScope(workspaceId, scope)) {
            return operation.get();
        }
    }

    private <T> T runWithinScopeRead(int workspaceId, RecordVisibilityScope scope, RecordVisibilityRead<T> read) {
        try (var ignored = enterScope(workspaceId, scope)) {
            return read.read();
        }
    }

    private OperationScopeBinding enterScope(int workspaceId, RecordVisibilityScope scope) {
        if (scope != null) {
            requireScopeIdentity(workspaceId, scope);
        }
        OperationScope previous = operationScope.get();
        operationScope.set(new OperationScope(workspaceId, tenantContext.getCatalog(), tenantContext.isResolved(), tenantContext.getOrgId(), scope));
        return new OperationScopeBinding(previous);
    }

    /**
     * Shares one eagerly resolved snapshot across an operation that changes no state. Routing,
     * identity validation and restoration match {@link #withWorkspaceScope(int, Supplier)}, but
     * the read invokes its own callback directly so it never enters the mutation callback merge.
     *
     * @param <T> the read's result type
     * @param workspaceId workspace anchoring the organization
     * @param read the read-only operation
     * @return the read's result
     */
    public <T> T withWorkspaceScopeRead(int workspaceId, RecordVisibilityRead<T> read) {
        return runWithinScopeRead(workspaceId, getForWorkspace(workspaceId), read);
    }

    /**
     * Reuses a caller's trusted snapshot for a read without joining the mutation callback interface.
     * Explicit single-database snapshots precede prepared data; routed snapshots must equal the
     * exact prepared payload. The enclosing operation is restored even when the read fails.
     *
     * @param <T> the read's result type
     * @param workspaceId workspace anchoring the organization
     * @param scope the trusted snapshot already resolved by the caller
     * @param read the read-only operation
     * @return the read's result
     */
    public <T> T withWorkspaceScopeRead(int workspaceId, RecordVisibilityScope scope, RecordVisibilityRead<T> read) {
        Objects.requireNonNull(scope, "scope");
        requireScopeIdentity(workspaceId, scope);
        if (shareWorkspaceControlAccess.isCatalogPerPlacement()
                && !scope.equals(getFreshForWorkspace(workspaceId))) {
            throw new TenantScopeSnapshotException("Operation scope does not match the prepared tenant context");
        }
        return runWithinScopeRead(workspaceId, scope, read);
    }

    /**
     * Shares a read-only operation's first needed snapshot and any ordinary lookup failure.
     * Empty and skipped operations perform no lookup, exactly as
     * {@link #withDeferredWorkspaceScope(int, Supplier)}. Routed reads still validate their prepared
     * snapshot on every access. The read never passes through the mutation callback interface.
     *
     * @param <T> the read's result type
     * @param workspaceId workspace anchoring the organization
     * @param read the read-only operation
     * @return the read's result
     */
    public <T> T withDeferredWorkspaceScopeRead(int workspaceId, RecordVisibilityRead<T> read) {
        OperationScope previous = operationScope.get();
        if (previous != null && previous.workspaceId == workspaceId) {
            return read.read();
        }
        return runWithinScopeRead(workspaceId, null, read);
    }

    /** Restores the enclosing operation without carrying either callback interface. */
    private final class OperationScopeBinding implements AutoCloseable {
        private final OperationScope previous;

        private OperationScopeBinding(OperationScope previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
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
     * then lazy lookup. A deferred operation's first needed scope follows the same precedence and
     * retains ordinary lookup failures, while snapshot-invariant refusals always propagate.
     * The transaction memo is suspended with its transaction and discarded on completion.
     * A transaction-first single-database entry retains its lazy behavior; at REPEATABLE READ its
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
        if (!routed && operation != null && operation.workspaceId == workspaceId) {
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
                RecordVisibilityScope scope = preparedOrTransactionScope(workspaceId);
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
        if (routed) {
            var prepared = tenantContext.getWorkspaceScopes();
            return prepared != null && prepared.workspaceId() == workspaceId
                ? prepared.requireSharing().recordVisibilityScope() : loadScope(workspaceId);
        }
        return preparedOrTransactionScope(workspaceId);
    }

    /** Identifies this deferred operation's original lookup failure without performing another read. */
    boolean isScopeResolutionFailure(int workspaceId, RuntimeException failure) {
        OperationScope operation = operationScope.get();
        return operation != null && operation.workspaceId == workspaceId && operation.failure == failure;
    }

    private RecordVisibilityScope preparedOrTransactionScope(int workspaceId) {
        var prepared = tenantContext.getWorkspaceScopes();
        if (prepared != null && prepared.workspaceId() == workspaceId) {
            return prepared.requireSharing().recordVisibilityScope();
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
