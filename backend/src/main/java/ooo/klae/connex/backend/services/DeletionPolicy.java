package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Enforces creator-or-admin deletion for workspace-owned content. */
@Component
@RequiredArgsConstructor
public class DeletionPolicy {
    private final WorkspaceService workspaceService;

    /**
     * Allows the creator or a built-in workspace admin or owner without a custom-role overlay
     * to delete the content. A missing creator fails closed to the administrator requirement.
     *
     * @param creatorUserId creator user id, or {@code null} when creator identity is unavailable
     */
    public void requireDeletable(Integer creatorUserId) {
        if (isCreator(creatorUserId)) {
            return;
        }
        workspaceService.requireBuiltInAdministrator();
    }

    /**
     * {@link #requireDeletable(Integer)} for callers whose lock order forces the administrator
     * snapshot to be taken before their record locks.
     *
     * @param creatorUserId creator user id, or {@code null} when creator identity is unavailable
     * @param lockedBuiltInAdministrator the verdict of
     *     {@link WorkspaceService#isLockedBuiltInAdministrator(int, int)} in this transaction
     */
    public void requireDeletable(Integer creatorUserId, boolean lockedBuiltInAdministrator) {
        if (isCreator(creatorUserId)) {
            return;
        }
        workspaceService.requireLockedBuiltInAdministrator(lockedBuiltInAdministrator);
    }

    private boolean isCreator(Integer creatorUserId) {
        return creatorUserId != null && creatorUserId == workspaceService.getCurrentUserId();
    }
}
