"use client";

import { createContext, useContext, useMemo, type ReactNode } from "react";

const PermissionsContext = createContext<ReadonlySet<string> | null>(null);

/**
 * Publishes the viewer's effective workspace permissions to client components.
 *
 * The app shell already resolves these server-side to gate navigation, so sharing them costs no
 * extra request and no loading state. The point is that a client component can ask whether it
 * may do something *before* trying: probing an endpoint and reading the 403 as "not allowed"
 * turns an error into control flow, logs a console error on ordinary loads, and cannot tell
 * "you may not" apart from "the request failed".
 *
 * This is a UX-availability hint only — the backend remains authoritative on every call. It is
 * fail-closed: when the lookup fails the shell passes an empty list, so a privileged affordance
 * is hidden rather than shown and then rejected.
 *
 * @param permissions - the viewer's effective permission keys in the active workspace
 * @param children - the tree that reads them
 */
export function PermissionsProvider({
    permissions,
    children,
}: {
    permissions: readonly string[];
    children: ReactNode;
}) {
    const granted = useMemo(() => new Set(permissions), [permissions]);
    return <PermissionsContext.Provider value={granted}>{children}</PermissionsContext.Provider>;
}

/**
 * Whether the viewer holds one effective permission in the active workspace.
 *
 * @param permission - the backend permission key, e.g. `CUSTOM_FIELD_MANAGE`
 * @returns true when the viewer holds it
 * @throws when called outside a {@link PermissionsProvider}
 */
export function usePermission(permission: string): boolean {
    const granted = useContext(PermissionsContext);
    if (granted === null) throw new Error("usePermission must be used within PermissionsProvider");
    return granted.has(permission);
}
