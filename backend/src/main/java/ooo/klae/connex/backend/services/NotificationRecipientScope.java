package ooo.klae.connex.backend.services;

import java.util.Map;

import ooo.klae.connex.backend.beans.NotificationMembership;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable recipient-authorized control snapshot reserved for NotificationMapper conversion
 * (#485 L9b). Each source workspace retains its own unfiltered organization ceiling; a union of
 * organizations would authorize forged cross-organization shares for a multi-organization member.
 * This read snapshot is never locking mutation authority and never substitutes for assistant
 * privacy-generation reads. Workspace names are hydrated from memberships after tenant SQL paging.
 */
public final class NotificationRecipientScope {
    private final int recipientId;
    private final Map<Integer, NotificationMembership> memberships;
    private final Map<Integer, RecordVisibilityScope> visibilityBySourceWorkspace;
    private final String membershipsJson;
    private final String sourceWorkspaceScopesJson;

    NotificationRecipientScope(
            int recipientId,
            Map<Integer, NotificationMembership> memberships,
            Map<Integer, RecordVisibilityScope> visibilityBySourceWorkspace,
            ObjectMapper objectMapper) {
        this.recipientId = recipientId;
        this.memberships = Map.copyOf(memberships);
        this.visibilityBySourceWorkspace = Map.copyOf(visibilityBySourceWorkspace);
        this.membershipsJson = objectMapper.writeValueAsString(memberships.values().stream()
            .sorted(java.util.Comparator.comparingInt(NotificationMembership::workspaceId))
            .map(member -> new MembershipRow(member.workspaceId(), member.status(), member.membershipId()))
            .toList());
        this.sourceWorkspaceScopesJson = objectMapper.writeValueAsString(
            visibilityBySourceWorkspace.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .flatMap(source -> source.getValue().workspaceIds().stream()
                    .map(owner -> new OwnerWorkspaceRow(source.getKey(), owner)))
                .toList());
    }

    /** Returns the server-selected recipient whose memberships authorized this snapshot. */
    public int recipientId() { return recipientId; }

    /** Returns unfiltered memberships and source-workspace labels keyed by workspace ID. */
    public Map<Integer, NotificationMembership> memberships() { return memberships; }

    /** Returns separately typed, unfiltered record ceilings keyed by notification workspace. */
    public Map<Integer, RecordVisibilityScope> visibilityBySourceWorkspace() { return visibilityBySourceWorkspace; }

    /** Returns membership rows containing workspaceId, status and membershipId for JSON_TABLE. */
    public String membershipsJson() { return membershipsJson; }

    /** Returns sourceWorkspaceId/id pairs; SQL must constrain both columns for every share branch. */
    public String sourceWorkspaceScopesJson() { return sourceWorkspaceScopesJson; }

    /** Refuses a source outside the recipient's captured memberships, rather than widening scope. */
    public RecordVisibilityScope requireVisibilityForWorkspace(int workspaceId) {
        RecordVisibilityScope scope = visibilityBySourceWorkspace.get(workspaceId);
        if (scope == null) {
            throw new TenantScopeSnapshotException("Notification source is outside the recipient scope");
        }
        return scope;
    }

    private record MembershipRow(int workspaceId, String status, long membershipId) { }
    private record OwnerWorkspaceRow(int sourceWorkspaceId, int id) { }
}
