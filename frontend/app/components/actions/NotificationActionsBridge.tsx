"use client";

import { useMemo } from "react";
import { useTranslations } from "next-intl";
import { BellAlertIcon } from "@heroicons/react/24/outline";

import { markAllNotificationsRead } from "@/app/lib/api";
import { toastSuccess } from "@/app/lib/toast";
import { useNotifications } from "@/app/hooks/useNotifications";
import { useRegisterActions } from "@/app/hooks/useActions";
import type { AppAction } from "@/app/lib/actions/types";
import { emitAllNotificationsRead } from "@/app/components/notifications/notificationEvents";

/**
 * Registers the notification actions that depend on the live notification context — currently "mark
 * all notifications read", which refreshes the unread count after the mutation. Rendered inside the
 * provider so it can bridge {@link useNotifications} into the registry. Renders nothing.
 */
export default function NotificationActionsBridge(): null {
    const { recipientId } = useNotifications();
    const t = useTranslations("Actions");

    const actions = useMemo<readonly AppAction[]>(
        () => [
            {
                id: "utility.mark-all-notifications-read",
                group: "utility",
                labelKey: "utility.markAllNotificationsRead",
                icon: BellAlertIcon,
                order: 20,
                execute: async () => {
                    const result = await markAllNotificationsRead();
                    emitAllNotificationsRead(recipientId, result);
                    toastSuccess(t("feedback.allNotificationsRead"));
                },
            },
        ],
        [recipientId, t],
    );

    useRegisterActions(actions);
    return null;
}
