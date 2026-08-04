"use client";

import { createContext, useCallback, useContext, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import type { OrganizationIdentity, Workspace, WorkspaceIdentity } from "@/app/lib/types";
import { createWorkspace, switchWorkspace } from "@/app/lib/api";
import {
    adoptWorkspaces,
    applyOrganizationIdentity,
    applyWorkspaceIdentity,
    restoreWorkspaceIdentity as restorePublishedWorkspaceIdentity,
} from "@/app/lib/workspaceSnapshot";

type PublishActiveWorkspace = (id: number | null) => void;
type PublishWorkspace = (workspace: Workspace) => void;
type SelectionChangeRunner = <T>(
    operation: (
        publishActiveWorkspace: PublishActiveWorkspace,
        publishWorkspace: PublishWorkspace,
    ) => Promise<T>,
) => Promise<T>;

type WorkspaceContextValue = {
    workspaces: Workspace[];
    activeWorkspaceId: number | null;
    activeWorkspace: Workspace | null;
    switching: boolean;
    runInWorkspace: (id: number, operation: (switched: boolean) => Promise<void>) => Promise<boolean>;
    runSelectionChange: SelectionChangeRunner;
    switchTo: (id: number) => Promise<void>;
    create: (name: string) => Promise<Workspace>;
    publishWorkspaceIdentity: (
        identity: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
    ) => void;
    restoreWorkspaceIdentity: (
        expected: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
        replacement: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
    ) => void;
    publishOrganizationIdentity: (identity: Pick<OrganizationIdentity, "id" | "name">) => void;
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
 * carries no generation to order two in-flight renders. Only one selection-changing operation at a
 * time is therefore permitted. Within a successful operation, the response cookie is
 * applied before that result is published to provider state, so serialization prevents another
 * selection response from inverting those two decisions.
 *
 * This is not a complete convergence guarantee. If a 200 response's body fails to read or parse,
 * the browser has already applied its cookie but the operation cannot publish its result. Because
 * `initialActiveId` is mount-only, nothing re-synchronizes the provider after that failure.
 * Recovery for that residual is tracked by
 * {@link https://github.com/itkla/connex/issues/1023 issue #1023}.
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

    const publishWorkspace = useCallback((workspace: Workspace) => {
        setWorkspaces((previous) => previous.some(({ id }) => id === workspace.id)
            ? previous.map((held) => held.id === workspace.id ? workspace : held)
            : [...previous, workspace]);
    }, []);

    const publishWorkspaceIdentity = useCallback((identity: Pick<
        WorkspaceIdentity,
        "id" | "name" | "slug" | "timezone"
    >) => {
        setWorkspaces((previous) => applyWorkspaceIdentity(previous, identity));
    }, []);

    const restoreWorkspaceIdentity = useCallback((
        expected: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
        replacement: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
    ) => {
        setWorkspaces((previous) => restorePublishedWorkspaceIdentity(previous, expected, replacement));
    }, []);

    const publishOrganizationIdentity = useCallback((identity: Pick<OrganizationIdentity, "id" | "name">) => {
        setWorkspaces((previous) => applyOrganizationIdentity(previous, identity));
    }, []);

    const runSelectionChange = useCallback(async <T,>(
        operation: (
            publishActiveWorkspace: PublishActiveWorkspace,
            publishWorkspace: PublishWorkspace,
        ) => Promise<T>,
    ) => {
        if (switchingRef.current) throw new Error("A workspace operation is already in progress");
        switchingRef.current = true;
        setSwitching(true);
        try {
            return await operation(publishActiveWorkspace, publishWorkspace);
        } finally {
            switchingRef.current = false;
            setSwitching(false);
        }
    }, [publishActiveWorkspace, publishWorkspace]);

    const runInWorkspace = useCallback(async (
        id: number,
        operation: (switched: boolean) => Promise<void>,
    ) => {
        if (switchingRef.current) return false;
        return runSelectionChange(async (publishActiveWorkspace) => {
            const switched = id !== activeWorkspaceIdRef.current;
            if (switched) {
                await switchWorkspace(id);
                publishActiveWorkspace(id);
            }
            await operation(switched);
            return true;
        });
    }, [runSelectionChange]);

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
        (name: string) => runSelectionChange(async (publishActiveWorkspace, publishWorkspace) => {
            const workspace = await createWorkspace(name);
            publishWorkspace(workspace);
            publishActiveWorkspace(workspace.id);
            router.replace("/dashboard");
            router.refresh();
            return workspace;
        }),
        [router, runSelectionChange],
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
            runSelectionChange,
            switchTo,
            create,
            publishWorkspaceIdentity,
            restoreWorkspaceIdentity,
            publishOrganizationIdentity,
        }),
        [
            workspaces,
            activeWorkspaceId,
            activeWorkspace,
            switching,
            runInWorkspace,
            runSelectionChange,
            switchTo,
            create,
            publishWorkspaceIdentity,
            restoreWorkspaceIdentity,
            publishOrganizationIdentity,
        ],
    );

    return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace() {
    const value = useContext(WorkspaceContext);
    if (!value) throw new Error("useWorkspace must be used within WorkspaceProvider");
    return value;
}
