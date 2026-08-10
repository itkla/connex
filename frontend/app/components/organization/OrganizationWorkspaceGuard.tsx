"use client";

import { useRouter } from "next/navigation";

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
    const router = useRouter();
    const { activeWorkspaceId } = useWorkspace();

    if (activeWorkspaceId !== workspaceId) {
        return <WorkspaceSelectionUnavailable onRetry={async () => router.refresh()} />;
    }

    return children;
}
