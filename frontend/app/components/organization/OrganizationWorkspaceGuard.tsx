"use client";

import WorkspaceSelectionUnavailable from "@/app/components/WorkspaceSelectionUnavailable";
import { useWorkspace } from "@/app/hooks/useWorkspace";

/** Withholds an organization payload that was resolved for a different active workspace. */
export default function OrganizationWorkspaceGuard({
    workspaceId,
    children,
}: {
    workspaceId: number;
    children?: React.ReactNode;
}) {
    const { activeWorkspaceId, retrySelectionRecovery } = useWorkspace();

    if (activeWorkspaceId !== workspaceId) {
        return <WorkspaceSelectionUnavailable onRetry={retrySelectionRecovery} />;
    }

    return children;
}
