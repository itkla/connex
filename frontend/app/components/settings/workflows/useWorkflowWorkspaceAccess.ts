"use client";

import { useEffect, useState } from "react";

import { useActions } from "@/app/hooks/useActions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { getWorkspaceMembers } from "@/app/lib/api";
import type { WorkspaceMember } from "@/app/lib/types";

type ResolvedMembership = {
    workspaceId: number;
    userId: number;
    members: WorkspaceMember[];
};

/** Resolves workflow authoring membership and the server-matching built-in SYSTEM gate. */
export function useWorkflowWorkspaceAccess() {
    const { activeWorkspaceId, switching } = useWorkspace();
    const { context } = useActions();
    const userId = context.user?.id ?? null;
    const [resolved, setResolved] = useState<ResolvedMembership | null>(null);

    useEffect(() => {
        if (activeWorkspaceId == null || userId == null || switching) return;
        const controller = new AbortController();
        void getWorkspaceMembers(activeWorkspaceId, {
            signal: controller.signal,
            headers: { "X-Workspace-Id": String(activeWorkspaceId) },
        }).then((members) => {
            if (!controller.signal.aborted) setResolved({ workspaceId: activeWorkspaceId, userId, members });
        }).catch(() => {
            if (!controller.signal.aborted) setResolved({ workspaceId: activeWorkspaceId, userId, members: [] });
        });
        return () => controller.abort();
    }, [activeWorkspaceId, switching, userId]);

    const current = !switching
        && resolved?.workspaceId === activeWorkspaceId
        && resolved.userId === userId
            ? resolved
            : null;
    const membership = current?.members.find((member) => member.id === userId) ?? null;
    const builtInRole = membership?.roleId == null
        && (membership?.role === "owner" || membership?.role === "admin");
    return {
        members: current?.members ?? [],
        canRunAsSystem: builtInRole,
        membershipResolved: current != null,
    };
}
