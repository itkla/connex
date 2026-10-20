package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
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

    /**
     * Resolves one immutable, unfiltered record-visibility ceiling for an operation.
     * Routed operations serve the immutable payload prepared before their first tenant transaction.
     * A missing or mismatched payload inside a routed transaction fails before any control read.
     * In single-database mode only, an actual transaction with synchronization reuses one scope per
     * workspace. The memo is suspended with that transaction and discarded on commit or rollback;
     * outside a transaction, every call reads a fresh snapshot. Catalog-per-placement mode bypasses
     * the memo entirely: every call reaches the boundary so the prepared snapshot supplied by
     * #2165 is validated against the current workspace, organization, and catalog.
     *
     * @param workspaceId workspace anchoring the organization
     * @return the organization's workspace IDs, including the anchor
     * @throws IllegalStateException when the anchor is invalid or the snapshot disagrees with
     *     the resolved tenant context
     */
    public RecordVisibilityScope getForWorkspace(int workspaceId) {
        if (shareWorkspaceControlAccess.isCatalogPerPlacement()
                || !TransactionSynchronizationManager.isActualTransactionActive()
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

    private RecordVisibilityScope loadScope(int workspaceId) {
        var snapshot = shareWorkspaceControlAccess.getForWorkspace(workspaceId);
        return snapshot.recordVisibilityScope();
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
