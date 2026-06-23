"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import type { Workspace } from "@/app/lib/types";
import { switchWorkspace } from "@/app/lib/api";

const WORKSPACE_COOKIE = "connex_workspace";

function writeWorkspaceCookie(id: number) {
    document.cookie = `${WORKSPACE_COOKIE}=${id};path=/;max-age=31536000;samesite=lax`;
}

type WorkspaceContextValue = {
    workspaces: Workspace[];
    activeWorkspaceId: number | null;
    activeWorkspace: Workspace | null;
    switching: boolean;
    switchTo: (id: number) => Promise<void>;
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
    const [workspaces] = useState(initialWorkspaces);
    const [activeWorkspaceId, setActiveWorkspaceId] = useState<number | null>(initialActiveId);
    const [switching, setSwitching] = useState(false);

    const switchTo = useCallback(
        async (id: number) => {
            if (id === activeWorkspaceId || switching) return;
            setSwitching(true);
            try {
                await switchWorkspace(id);
                writeWorkspaceCookie(id);
                setActiveWorkspaceId(id);
                // Re-run all server components (SSR lists) under the new workspace.
                router.refresh();
            } finally {
                setSwitching(false);
            }
        },
        [activeWorkspaceId, switching, router],
    );

    const activeWorkspace = useMemo(
        () => workspaces.find((w) => w.id === activeWorkspaceId) ?? null,
        [workspaces, activeWorkspaceId],
    );

    const value = useMemo(
        () => ({ workspaces, activeWorkspaceId, activeWorkspace, switching, switchTo }),
        [workspaces, activeWorkspaceId, activeWorkspace, switching, switchTo],
    );

    return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace() {
    const value = useContext(WorkspaceContext);
    if (!value) throw new Error("useWorkspace must be used within WorkspaceProvider");
    return value;
}
