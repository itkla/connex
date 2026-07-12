"use client";

import { createContext, useCallback, useContext, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import type { Workspace } from "@/app/lib/types";
import { createWorkspace, switchWorkspace } from "@/app/lib/api";

const WORKSPACE_COOKIE = "connex_workspace";

function writeWorkspaceCookie(id: number) {
    document.cookie = `${WORKSPACE_COOKIE}=${id};path=/;max-age=31536000;samesite=lax`;
}

function readWorkspaceCookie() {
    const match = document.cookie.match(/(?:^|;\s*)connex_workspace=(\d+)/);
    if (!match) return null;
    const id = Number(match[1]);
    return Number.isSafeInteger(id) && id > 0 ? id : null;
}

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
    const activeWorkspaceIdRef = useRef(initialActiveId);
    const switchingRef = useRef(false);

    const runInWorkspace = useCallback(async (
        id: number,
        operation: (switched: boolean) => Promise<void>,
    ) => {
        if (switchingRef.current) return false;
        switchingRef.current = true;
        setSwitching(true);
        try {
            const cookieWorkspaceId = readWorkspaceCookie();
            if (cookieWorkspaceId !== activeWorkspaceIdRef.current) {
                activeWorkspaceIdRef.current = cookieWorkspaceId;
                setActiveWorkspaceId(cookieWorkspaceId);
            }
            const switched = id !== cookieWorkspaceId;
            if (switched) {
                await switchWorkspace(id);
                writeWorkspaceCookie(id);
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
                writeWorkspaceCookie(workspace.id);
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
