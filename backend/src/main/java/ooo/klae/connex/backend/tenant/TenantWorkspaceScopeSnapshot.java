package ooo.klae.connex.backend.tenant;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;

import java.util.Objects;
import java.util.Optional;

import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess.OrganizationWorkspaces;

/**
 * Immutable control data prepared before a workspace operation starts its tenant transactions in either mode.
 * An absent sharing anchor and an inactive read anchor are retained as results, so trusted cleanup
 * can continue after lifecycle fencing or root deletion without authorizing a later scope read.
 * Workspace changes after preparation become visible to the next operation. Explicit destructive
 * freshness boundaries bypass this payload in single-database mode.
 */
public record TenantWorkspaceScopeSnapshot(
        int workspaceId,
        int orgId,
        String catalog,
        Optional<OrganizationWorkspaces> sharing,
        WorkspaceScope active) {

    public TenantWorkspaceScopeSnapshot {
        Objects.requireNonNull(sharing, "sharing");
        Objects.requireNonNull(active, "active");
        if (active.orgId() != orgId || sharing.filter(scope -> scope.orgId() != orgId).isPresent()) {
            throw new IllegalArgumentException("Prepared workspace scopes must belong to the routed organization");
        }
    }

    /** Whether this payload belongs to the exact identity and independently resolved catalog. */
    public boolean matches(int requestedWorkspaceId, int requestedOrgId, String requestedCatalog) {
        return workspaceId == requestedWorkspaceId && orgId == requestedOrgId
            && Objects.equals(catalog, requestedCatalog);
    }

    /** Returns the unfiltered snapshot, preserving the existing missing-anchor refusal. */
    public OrganizationWorkspaces requireSharing() {
        return sharing.orElseThrow(() -> new TenantScopeSnapshotException(
            "Workspace " + workspaceId + " does not exist"));
    }

    /** Returns the active-only snapshot only when its anchor still existed at preparation. */
    public WorkspaceScope requireActiveWorkspace() {
        if (!active.workspaceIds().contains(workspaceId)) {
            throw new TenantScopeSnapshotException("Workspace " + workspaceId + " does not exist");
        }
        return active;
    }
}
