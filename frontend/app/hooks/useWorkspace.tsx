"use client";

import { createContext, useCallback, useContext, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import type { Workspace } from "@/app/lib/types";
import { createWorkspace, switchWorkspace } from "@/app/lib/api";
import { adoptWorkspaces } from "@/app/lib/workspaceSnapshot";

type WorkspaceContextValue = {
    workspaces: Workspace[];
    activeWorkspaceId: number | null;
    activeWorkspace: Workspace | null;
    switching: boolean;
    runInWorkspace: (id: number, operation: (switched: boolean) => Promise<void>) => Promise<boolean>;
    adoptActiveWorkspace: (id: number | null) => void;
    switchTo: (id: number) => Promise<void>;
    create: (name: string) => Promise<Workspace>;
};

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

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
 * Which workspace is active is never adopted from a refreshed prop. A server render that began
 * before {@link WorkspaceContextValue.switchTo} set the cookie can resolve after it, and the payload
 * carries no generation to order two in-flight renders. Operations that authoritatively change the
 * selection publish their own result through this provider instead. Explicit adoption publishes
 * immediately even while another workspace operation is in progress. If that operation later
 * succeeds, its later response publishes again and wins, matching the order in which the browser
 * applies the responses' workspace cookies. If it fails, the adopted decision remains published.
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

    const publishActiveWorkspace = useCallback((id: number | null) => {
        activeWorkspaceIdRef.current = id;
        setActiveWorkspaceId(id);
    }, []);

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
                publishActiveWorkspace(id);
            }
            await operation(switched);
            return true;
        } finally {
            switchingRef.current = false;
            setSwitching(false);
        }
    }, [publishActiveWorkspace]);

    const adoptActiveWorkspace = useCallback((id: number | null) => {
        publishActiveWorkspace(id);
    }, [publishActiveWorkspace]);

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
                publishActiveWorkspace(workspace.id);
                router.replace("/dashboard");
                router.refresh();
                return workspace;
            } finally {
                switchingRef.current = false;
                setSwitching(false);
            }
        },
        [publishActiveWorkspace, router],
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
            adoptActiveWorkspace,
            switchTo,
            create,
        }),
        [
            workspaces,
            activeWorkspaceId,
            activeWorkspace,
            switching,
            runInWorkspace,
            adoptActiveWorkspace,
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
