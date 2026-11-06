"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";

import { safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { requestGuardedNavigation } from "@/app/hooks/useUnsavedChangesGuard";
import { navigateAfterWorkspaceSelection } from "@/app/lib/renderedWorkspace";
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
    const { activeWorkspaceId, runInWorkspace } = useWorkspace();

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
            return new Promise<boolean>((resolve, reject) => {
                requestGuardedNavigation(async () => {
                    try {
                        if (workspaceId === "global") {
                            await beforeOpen();
                            navigateAfterWorkspaceSelection(() => router.push(url));
                            resolve(true);
                        } else {
                            resolve(await runInWorkspace(workspaceId, async (switched) => {
                                await beforeOpen();
                                navigateAfterWorkspaceSelection(() => {
                                    if (switched) {
                                        router.replace(url);
                                        router.refresh();
                                    } else {
                                        router.push(url);
                                    }
                                });
                            }));
                        }
                    } catch (error) {
                        reject(error);
                    }
                }, { onCancel: () => resolve(true) });
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
        async (
            notification: Notification,
            action: () => Promise<void>,
            switchedDestination = "/dashboard",
        ) => {
            const workspaceId = targetWorkspaceId(notification);
            if (workspaceId == null) return false;
            if (workspaceId === "global") return false;
            const execute = () => runInWorkspace(workspaceId, async (switched) => {
                try {
                    await action();
                } finally {
                    if (switched) {
                        navigateAfterWorkspaceSelection(() => {
                            router.replace(switchedDestination);
                            router.refresh();
                        });
                    }
                }
            });
            if (workspaceId === activeWorkspaceId) return execute();
            return new Promise<boolean>((resolve, reject) => {
                requestGuardedNavigation(() => execute().then(resolve, reject), { onCancel: () => resolve(true) });
            });
        },
        [activeWorkspaceId, router, runInWorkspace],
    );

    return {
        executeInNotificationWorkspace,
        openInNotificationWorkspace,
        openNotification,
    };
}
