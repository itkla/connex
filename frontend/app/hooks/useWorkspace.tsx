"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import type { Workspace } from "@/app/lib/types";
import { createWorkspace, switchWorkspace } from "@/app/lib/api";

type WorkspaceContextValue = {
    workspaces: Workspace[];
    activeWorkspaceId: number | null;
    activeWorkspace: Workspace | null;
    switching: boolean;
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

    const switchTo = useCallback(
        async (id: number) => {
            if (id === activeWorkspaceId || switching) return;
            setSwitching(true);
            try {
                await switchWorkspace(id);
                setActiveWorkspaceId(id);
                router.replace("/dashboard");
                router.refresh();
            } finally {
                setSwitching(false);
            }
        },
        [activeWorkspaceId, switching, router],
    );

    const create = useCallback(
        async (name: string) => {
            const workspace = await createWorkspace(name);
            setWorkspaces((prev) => [...prev, workspace]);
            setActiveWorkspaceId(workspace.id);
            router.replace("/dashboard");
            router.refresh();
            return workspace;
        },
        [router],
    );

    const activeWorkspace = useMemo(
        () => workspaces.find((w) => w.id === activeWorkspaceId) ?? null,
        [workspaces, activeWorkspaceId],
    );

    const value = useMemo(
        () => ({ workspaces, activeWorkspaceId, activeWorkspace, switching, switchTo, create }),
        [workspaces, activeWorkspaceId, activeWorkspace, switching, switchTo, create],
    );

    return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace() {
    const value = useContext(WorkspaceContext);
    if (!value) throw new Error("useWorkspace must be used within WorkspaceProvider");
    return value;
}
