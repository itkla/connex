"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";

import { safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { type Notification } from "@/app/lib/types";

function targetWorkspaceId(notification: Notification) {
    if (notification.type === "workspace.join") return "global";
    const workspaceId = notification.workspaceId;
    return typeof workspaceId === "number"
        && Number.isSafeInteger(workspaceId)
        && workspaceId > 0
        ? workspaceId
        : null;
}

/** Coordinates recipient-wide notification actions with the notification's owning workspace. */
export function useNotificationWorkspaceActions() {
    const router = useRouter();
    const { runInWorkspace } = useWorkspace();

    const openInNotificationWorkspace = useCallback(
        async (
            notification: Notification,
            destination: string | null | undefined,
            beforeOpen: () => Promise<void> = async () => {},
        ) => {
            const url = safeNotificationUrl(destination);
            if (!url) return true;
            const workspaceId = targetWorkspaceId(notification);
            if (workspaceId == null) return false;
            if (workspaceId === "global") {
                await beforeOpen();
                router.push(url);
                return true;
            }
            return runInWorkspace(workspaceId, async (switched) => {
                await beforeOpen();
                if (switched) {
                    router.replace(url);
                    router.refresh();
                } else {
                    router.push(url);
                }
            });
        },
        [router, runInWorkspace],
    );

    const openNotification = useCallback(
        (notification: Notification) => openInNotificationWorkspace(
            notification, notification.actionUrl),
        [openInNotificationWorkspace],
    );

    const executeInNotificationWorkspace = useCallback(
        async (notification: Notification, action: () => Promise<void>) => {
            const workspaceId = targetWorkspaceId(notification);
            if (workspaceId == null) return false;
            if (workspaceId === "global") return false;
            return runInWorkspace(workspaceId, async (switched) => {
                try {
                    await action();
                } finally {
                    if (switched) {
                        router.replace("/dashboard");
                        router.refresh();
                    }
                }
            });
        },
        [router, runInWorkspace],
    );

    return {
        executeInNotificationWorkspace,
        openInNotificationWorkspace,
        openNotification,
    };
}
