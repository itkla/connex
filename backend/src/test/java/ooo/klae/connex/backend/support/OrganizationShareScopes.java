package ooo.klae.connex.backend.support;

import java.util.stream.Collectors;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Builds the control-derived organization workspace allowlist that {@code ShareMapper}'s grant
 * statements enforce their same-organization ceiling against.
 *
 * <p>In production {@code ShareService} loads this snapshot through
 * {@code ShareWorkspaceControlAccess}, on the control catalog and outside any tenant transaction.
 * A test that calls the grant statements directly — the defense-in-depth proofs, and the many
 * fixtures that need a shared record — supplies the same allowlist through this helper, so a
 * fixture grant stays a same-organization grant and a forged allowlist stays visible as one.
 */
public final class OrganizationShareScopes {

    private OrganizationShareScopes() {
    }

    /**
     * Builds the allowlist for the organization owning a workspace.
     *
     * @param workspaceMapper control-plane mapper to read through
     * @param workspaceId workspace anchoring the organization
     * @return a JSON array of that organization's workspace ids
     */
    public static String orgWorkspaceIdsJson(WorkspaceMapper workspaceMapper, int workspaceId) {
        return workspaceMapper.findOrganizationWorkspacesForShare(workspaceId).stream()
            .map(Workspace::getId)
            .distinct()
            .map(String::valueOf)
            .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Builds an arbitrary allowlist, for tests that forge one.
     *
     * @param workspaceIds workspace ids the caller claims share one organization
     * @return a JSON array of those ids
     */
    public static String workspaceIdsJson(int... workspaceIds) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < workspaceIds.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(workspaceIds[index]);
        }
        return json.append(']').toString();
    }
}
