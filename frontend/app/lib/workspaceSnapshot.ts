import type { Workspace } from "@/app/lib/types";

/**
 * Merges a freshly arrived server payload over the workspaces currently published to the tree.
 *
 * The payload wins, except for workspaces the server has never mentioned. `WorkspaceProvider`'s
 * `create` and pending-membership acceptance publish their returned workspace before the refresh
 * that will report it, so a server render that began earlier can arrive with that workspace
 * missing. Dropping it there would not merely flicker: the active workspace is already the new one,
 * so it would name a workspace absent from the list and `activeWorkspace` would resolve to null,
 * leaving the shell with no active workspace until some later refresh happened to repair it.
 *
 * Absence is only treated as an addition when the previously consumed payload did not mention the
 * workspace either. A workspace that was in the last payload and is gone from this one was removed
 * — the viewer left it, or lost their membership — and must not be resurrected.
 *
 * @param held - the workspaces currently published to the tree
 * @param consumed - the payload those were last reconciled against
 * @param arriving - the payload just received from the server
 * @returns the workspaces to publish, or `arriving` itself when nothing had to be held back
 */
export function adoptWorkspaces(
    held: readonly Workspace[],
    consumed: readonly Workspace[],
    arriving: Workspace[],
): Workspace[] {
    const consumedIds = new Set(consumed.map((workspace) => workspace.id));
    const arrivingIds = new Set(arriving.map((workspace) => workspace.id));
    const unacknowledged = held.filter(
        (workspace) => !arrivingIds.has(workspace.id) && !consumedIds.has(workspace.id),
    );
    return unacknowledged.length === 0 ? arriving : [...arriving, ...unacknowledged];
}
