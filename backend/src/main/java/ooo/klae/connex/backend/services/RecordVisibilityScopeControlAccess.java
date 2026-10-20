package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

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

    /**
     * Resolves one immutable, unfiltered record-visibility ceiling for an operation.
     * Routed operations serve the immutable payload prepared before their first tenant transaction.
     * A missing or mismatched payload inside a routed transaction fails before any control read.
     * Single-database operations retain their lazy lookup and existing transaction behavior.
     *
     * @param workspaceId workspace anchoring the organization
     * @return the organization's workspace IDs, including the anchor
     * @throws IllegalStateException when the anchor is invalid or the snapshot disagrees with
     *     the resolved tenant context
     */
    public RecordVisibilityScope getForWorkspace(int workspaceId) {
        var snapshot = shareWorkspaceControlAccess.getForWorkspace(workspaceId);
        return snapshot.recordVisibilityScope();
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
