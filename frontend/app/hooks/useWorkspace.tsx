"use client";

import { createContext, useCallback, useContext, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import type { Workspace } from "@/app/lib/types";
import { createWorkspace, switchWorkspace } from "@/app/lib/api";

type WorkspaceContextValue = {
    workspaces: Workspace[];
    activeWorkspaceId: number | null;
    activeWorkspace: Workspace | null;
    switching: boolean;
    runInWorkspace: (id: number, operation: (switched: boolean) => Promise<void>) => Promise<boolean>;
    switchTo: (id: number) => Promise<void>;
    create: (name: string) => Promise<Workspace>;
};

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

/**
 * Merges a freshly arrived server payload over the currently held workspaces.
 *
 * The payload wins, except for workspaces the server has never mentioned. {@link
 * WorkspaceContextValue.create} appends the workspace it just created before the refresh that will
 * report it, so a server render that began earlier can arrive with the creation missing. Dropping
 * it there would not merely flicker: the active workspace is already the new one, so it would name
 * a workspace absent from the list and `activeWorkspace` would resolve to null, leaving the shell
 * with no active workspace until some later refresh happened to repair it.
 *
 * Absence is only treated as an addition when the previously consumed payload did not mention the
 * workspace either. A workspace that was in the last payload and is gone from this one was removed
 * — the viewer left it, or lost their membership — and must not be resurrected.
 *
 * @param held - the workspaces currently published to the tree
 * @param consumed - the payload those were last reconciled against
 * @param arriving - the payload just received from the server
 * @returns the workspaces to publish
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

/**
 * Publishes the viewer's workspaces and which one is active to client components.
 *
 * The workspace list is kept in step with the app shell's server-side read rather than frozen at
 * mount. Next preserves this provider across client-side navigation and merges a `router.refresh()`
 * payload without discarding client state, so a list seeded once from props never hears about a
 * later server render. That is what strands `activeWorkspace.role` after the viewer changes their
 * own membership: the members list correctly reads "Member" while every gate derived from the
 * workspace snapshot — the role selects, invite and domain administration, the member-removal menu
 * — keeps offering owner chrome whose mutations the backend then refuses.
 *
 * Adopting the payload during render rather than from an effect is deliberate: an effect would
 * commit one frame of exactly that stale chrome before correcting it, which is the defect in
 * miniature. {@link adoptWorkspaces} settles what a payload may overwrite.
 *
 * Which workspace is active is **not** adopted from props, and that is a known gap rather than an
 * invariant — see #1021. Four endpoints move it by writing the session cookie without going through
 * this provider (accepting an invite, accepting an invite link, accepting a pending membership, and
 * leaving a workspace, each followed by a bare `router.refresh()`), so the cookie and the payload
 * can name a workspace this provider does not. Syncing it from props is not the fix: a server
 * render that began before {@link WorkspaceContextValue.switchTo} set the cookie can resolve after
 * it, and the payload carries no generation to order two in-flight renders by, so adopting it
 * blindly trades a stale active workspace for a non-deterministic one. The role staleness this
 * sync does address lives entirely in the list.
 *
 * @param initialWorkspaces - the viewer's workspaces as of the current server render
 * @param initialActiveId - the workspace the session cookie selects, or null when none is
 * @param children - the tree that reads them
 */
export function WorkspaceProvider({
    initialWorkspaces,
    initialActiveId,
    children,
}: {
    initialWorkspaces: Workspace[];
    initialActiveId: number | null;
    children: React.ReactNode;
}) {
    const router = useRouter();
    const [workspaces, setWorkspaces] = useState(initialWorkspaces);
    const [activeWorkspaceId, setActiveWorkspaceId] = useState<number | null>(initialActiveId);
    const [switching, setSwitching] = useState(false);
    const [publishedWorkspaces, setPublishedWorkspaces] = useState(initialWorkspaces);
    const activeWorkspaceIdRef = useRef(initialActiveId);
    const switchingRef = useRef(false);

    if (publishedWorkspaces !== initialWorkspaces) {
        setPublishedWorkspaces(initialWorkspaces);
        setWorkspaces(adoptWorkspaces(workspaces, publishedWorkspaces, initialWorkspaces));
    }

    const runInWorkspace = useCallback(async (
        id: number,
        operation: (switched: boolean) => Promise<void>,
    ) => {
        if (switchingRef.current) return false;
        switchingRef.current = true;
        setSwitching(true);
        try {
            const switched = id !== activeWorkspaceIdRef.current;
            if (switched) {
                await switchWorkspace(id);
                activeWorkspaceIdRef.current = id;
                setActiveWorkspaceId(id);
            }
            await operation(switched);
            return true;
        } finally {
            switchingRef.current = false;
            setSwitching(false);
        }
    }, []);

    const switchTo = useCallback(
        async (id: number) => {
            await runInWorkspace(id, async () => {
                router.replace("/dashboard");
                router.refresh();
            });
        },
        [router, runInWorkspace],
    );

    const create = useCallback(
        async (name: string) => {
            if (switchingRef.current) throw new Error("A workspace operation is already in progress");
            switchingRef.current = true;
            setSwitching(true);
            try {
                const workspace = await createWorkspace(name);
                setWorkspaces((prev) => [...prev, workspace]);
                activeWorkspaceIdRef.current = workspace.id;
                setActiveWorkspaceId(workspace.id);
                router.replace("/dashboard");
                router.refresh();
                return workspace;
            } finally {
                switchingRef.current = false;
                setSwitching(false);
            }
        },
        [router],
    );

    const activeWorkspace = useMemo(
        () => workspaces.find((w) => w.id === activeWorkspaceId) ?? null,
        [workspaces, activeWorkspaceId],
    );

    const value = useMemo(
        () => ({
            workspaces,
            activeWorkspaceId,
            activeWorkspace,
            switching,
            runInWorkspace,
            switchTo,
            create,
        }),
        [
            workspaces,
            activeWorkspaceId,
            activeWorkspace,
            switching,
            runInWorkspace,
            switchTo,
            create,
        ],
    );

    return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace() {
    const value = useContext(WorkspaceContext);
    if (!value) throw new Error("useWorkspace must be used within WorkspaceProvider");
    return value;
}
