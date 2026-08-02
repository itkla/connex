/**
 * Whether the viewer's effective permissions could be resolved at all.
 *
 * Kept alongside the permission set rather than folded into it, because collapsing the two —
 * which {@code result.ok ? result.data : []} does — leaves "your role does not include this"
 * and "we could not determine your role" indistinguishable. A gate that cannot tell them apart
 * has to answer a transient lookup failure with a definitive denial, which strands an admin on
 * a dead end telling them to ask an administrator.
 */
export type PermissionsStatus = "resolved" | "unavailable";

/**
 * Outcome of asking whether the viewer holds one permission — the same distinction
 * {@code recordAccess.ts} draws between a record that is missing and one that is forbidden,
 * applied to the permission lookup itself.
 */
export type PermissionCheck = "granted" | "denied" | "unavailable";

/**
 * Classifies one permission against the viewer's resolved set.
 *
 * Reports {@code unavailable} ahead of any membership test: when the lookup failed the set is
 * empty for want of an answer, not because the role is empty, so answering {@code denied} from
 * it would state a verdict that was never reached.
 *
 * @param status - whether the effective-permission lookup succeeded
 * @param granted - the viewer's effective permission keys, empty when the lookup failed
 * @param permission - the backend permission key, e.g. {@code CUSTOM_FIELD_MANAGE}
 * @returns whether the viewer holds it, does not hold it, or could not be checked
 */
export function checkPermission(
    status: PermissionsStatus,
    granted: ReadonlySet<string>,
    permission: string,
): PermissionCheck {
    if (status === "unavailable") return "unavailable";
    return granted.has(permission) ? "granted" : "denied";
}

/**
 * Whether a freshly probed permission set answers differently from what is currently published,
 * and so whether the app shell needs to be re-rendered from the server.
 *
 * A published {@code unavailable} always counts as drift: any answer at all supersedes never
 * having had one. Otherwise the comparison is by membership, so the backend reordering its
 * response cannot masquerade as a role change and trigger a pointless refresh.
 *
 * @param status - the currently published lookup outcome
 * @param granted - the currently published permission keys
 * @param probed - the keys just read back from the server
 * @returns whether the published state is now stale
 */
export function permissionsDrifted(
    status: PermissionsStatus,
    granted: ReadonlySet<string>,
    probed: readonly string[],
): boolean {
    if (status === "unavailable") return true;
    const seen = new Set(probed);
    if (seen.size !== granted.size) return true;
    for (const permission of seen) {
        if (!granted.has(permission)) return true;
    }
    return false;
}
