"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, type ReactNode } from "react";
import { useRouter } from "next/navigation";

import { getEffectivePermissions } from "@/app/lib/api";
import {
    checkPermission,
    permissionsDrifted,
    type PermissionCheck,
    type PermissionsStatus,
} from "@/app/lib/permissionState";

type PermissionsContextValue = {
    status: PermissionsStatus;
    granted: ReadonlySet<string>;
    refresh: () => Promise<void>;
};

const PermissionsContext = createContext<PermissionsContextValue | null>(null);

/**
 * Publishes the viewer's effective workspace permissions, and the outcome of looking them up, to
 * client components.
 *
 * The app shell already resolves these server-side to gate navigation, so sharing them costs no
 * extra request and no loading state. The point is that a client component can ask whether it
 * may do something *before* trying: probing an endpoint and reading the 403 as "not allowed"
 * turns an error into control flow, logs a console error on ordinary loads, and cannot tell
 * "you may not" apart from "the request failed".
 *
 * This is a UX-availability hint only — the backend remains authoritative on every call. It is
 * fail-closed: when the lookup fails the shell passes an empty list and an {@code unavailable}
 * status, so a privileged affordance is hidden rather than shown and then rejected, while a
 * route-level refusal can still say the check failed instead of inventing a verdict.
 *
 * The published snapshot is kept live rather than treated as session-long state. Next preserves
 * this layout across client-side navigation, so without revalidation a role change would not
 * reach the viewer until a full page load: a newly granted user would keep being refused, and a
 * revoked one would keep being offered controls whose mutations the backend then rejects. On
 * regaining focus the provider re-reads the permission list — cheap, and never throttled, so
 * detection is not delayed — and asks Next to re-render the server tree only when the answer
 * actually changed. Refreshing the whole tree rather than patching local state is deliberate:
 * the sidebar, the command palette and the server-gated routes are derived from the same shell
 * lookup, so re-seeding all of them together keeps client and server gates from disagreeing.
 *
 * A failed probe changes nothing. {@code unavailable} means "we never managed to check", and a
 * momentary network blip is no reason to demote a session that already has a good answer.
 *
 * @param permissions - the viewer's effective permission keys in the active workspace
 * @param status - whether the shell's lookup succeeded
 * @param children - the tree that reads them
 */
export function PermissionsProvider({
    permissions,
    status,
    children,
}: {
    permissions: readonly string[];
    status: PermissionsStatus;
    children: ReactNode;
}) {
    const router = useRouter();
    const granted = useMemo(() => new Set(permissions), [permissions]);
    const probe = useRef<Promise<void> | null>(null);

    const refresh = useCallback(() => {
        if (probe.current !== null) return probe.current;
        const pending = (async () => {
            try {
                const probed = await getEffectivePermissions();
                if (permissionsDrifted(status, granted, probed)) router.refresh();
            } catch {
                return;
            }
        })().finally(() => {
            probe.current = null;
        });
        probe.current = pending;
        return pending;
    }, [granted, router, status]);

    useEffect(() => {
        const refreshWhenVisible = () => {
            if (document.hidden) return;
            void refresh();
        };
        window.addEventListener("focus", refreshWhenVisible);
        document.addEventListener("visibilitychange", refreshWhenVisible);
        return () => {
            window.removeEventListener("focus", refreshWhenVisible);
            document.removeEventListener("visibilitychange", refreshWhenVisible);
        };
    }, [refresh]);

    const value = useMemo(() => ({ status, granted, refresh }), [status, granted, refresh]);
    return <PermissionsContext.Provider value={value}>{children}</PermissionsContext.Provider>;
}

function usePermissionsContext(): PermissionsContextValue {
    const value = useContext(PermissionsContext);
    if (value === null) throw new Error("Permission hooks must be used within PermissionsProvider");
    return value;
}

/**
 * Whether the viewer holds one effective permission in the active workspace.
 *
 * Fail-closed: an unresolved lookup answers false, so an affordance stays hidden rather than
 * being offered and then refused. Use {@link usePermissionCheck} where the difference between
 * "you may not" and "we could not check" is worth telling the user about.
 *
 * @param permission - the backend permission key, e.g. `CUSTOM_FIELD_MANAGE`
 * @returns true when the viewer holds it
 * @throws when called outside a {@link PermissionsProvider}
 */
export function usePermission(permission: string): boolean {
    return usePermissionCheck(permission) === "granted";
}

/**
 * The viewer's standing on one effective permission, keeping a denial apart from a failed check.
 *
 * @param permission - the backend permission key, e.g. `CUSTOM_FIELD_MANAGE`
 * @returns whether the viewer holds it, does not hold it, or could not be checked
 * @throws when called outside a {@link PermissionsProvider}
 */
export function usePermissionCheck(permission: string): PermissionCheck {
    const { status, granted } = usePermissionsContext();
    return checkPermission(status, granted, permission);
}

/**
 * Re-reads the viewer's effective permissions and re-renders the server tree if they changed.
 *
 * For a surface offering the viewer a way out of a failed permission lookup, rather than waiting
 * for the next focus revalidation. Callers arriving while a probe is already running share it
 * instead of starting a second one.
 *
 * Reports no outcome, because it can observe none worth reporting: the client probe and the
 * shell's own server-side lookup fail independently, so a probe that reached the backend is no
 * evidence the page will recover. Whether it recovered is what the caller can already see — the
 * refused surface is replaced, or it is not.
 *
 * @returns a function resolving once the probe, and any refresh it triggered, has been issued
 * @throws when called outside a {@link PermissionsProvider}
 */
export function usePermissionsRefresh(): () => Promise<void> {
    return usePermissionsContext().refresh;
}
