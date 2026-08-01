import type { WorkspaceRole } from '@/app/lib/types';

/**
 * Mirrors the backend {@code DeletionPolicy}: a record may be deleted by its creator, or by a
 * workspace admin/owner. A record with no recorded creator is admin/owner-only, matching the
 * server's fail-closed behaviour.
 *
 * This only hides affordances. The server remains the enforcement point, so a stale or spoofed
 * client can never widen what a caller is actually allowed to delete.
 */
export function canDeleteOwnedRecord(
    creatorUserId: number | null | undefined,
    currentUserId: number,
    role: WorkspaceRole | null | undefined,
): boolean {
    if (creatorUserId != null && creatorUserId === currentUserId) {
        return true;
    }
    return role === 'admin' || role === 'owner';
}
