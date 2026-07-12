"use client";

import { BellAlertIcon, XMarkIcon } from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useState } from "react";

import { completeTask, dismissNotification, getContextNotifications } from "@/app/lib/api";
import { type Notification } from "@/app/lib/types";
import { toastError } from "@/app/lib/toast";
import { useNotifications } from "@/app/hooks/useNotifications";
import { notificationContent, notificationSeverityStyle } from "@/app/components/notifications/notificationContent";
import {
    emitNotificationStateChanged,
    onNotificationStateChanged,
} from "@/app/components/notifications/notificationEvents";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export default function EntityNotificationBanner({
    initialNotifications,
    contextType,
    contextId,
    initialStateVersion,
}: {
    initialNotifications: Notification[];
    contextType: string;
    contextId: number;
    initialStateVersion: number;
}) {
    const t = useTranslations("Notifications");
    const locale = useLocale();
    const { recipientId, refreshUnread } = useNotifications();
    const [items, setItems] = useState(initialNotifications);

    useEffect(() => {
        let active = true;
        let generation = 0;
        let reconciledVersion = initialStateVersion;
        let requiredVersion = 0;
        let retryId: number | null = null;
        const reconcile = async (stateVersion: number, canRetry: boolean, forceRefresh = false) => {
            requiredVersion = Math.max(requiredVersion, stateVersion);
            if (!forceRefresh && requiredVersion <= reconciledVersion) return;
            const requestGeneration = ++generation;
            try {
                const page = await getContextNotifications(contextType, contextId);
                if (!active || requestGeneration !== generation) return;
                if (page.stateVersion < requiredVersion && canRetry) {
                    if (retryId != null) window.clearTimeout(retryId);
                    retryId = window.setTimeout(
                        () => void reconcile(requiredVersion, false, forceRefresh),
                        250,
                    );
                    return;
                }
                if (page.stateVersion < requiredVersion) return;
                reconciledVersion = page.stateVersion;
                setItems(page.items);
            } catch {
                return;
            }
        };
        const stopStateChanged = onNotificationStateChanged(
            recipientId,
            ({ stateVersion, forceRefresh }) => void reconcile(stateVersion, true, forceRefresh),
            { replay: true },
        );
        return () => {
            active = false;
            generation += 1;
            if (retryId != null) window.clearTimeout(retryId);
            stopStateChanged();
        };
    }, [contextId, contextType, initialStateVersion, recipientId]);

    async function dismiss(item: Notification) {
        try {
            const updated = await dismissNotification(item.id);
            if (updated.stateVersion != null) {
                emitNotificationStateChanged(recipientId, updated.stateVersion);
            }
            setItems((current) => current.filter((entry) => entry.id !== item.id));
            await refreshUnread();
        } catch {
            toastError(t("actionError"));
        }
    }

    async function finishTask(item: Notification) {
        if (item.sourceType !== "task" || item.sourceId == null) return;
        try {
            await completeTask(item.sourceId);
            setItems((current) => current.filter((entry) => entry.id !== item.id));
            await refreshUnread();
        } catch {
            toastError(t("completeError"));
        }
    }

    if (items.length === 0) return null;

    return (
        <div className="mt-6 grid gap-3">
            {items.map((item) => {
                const content = notificationContent(item, t, locale);
                const style = notificationSeverityStyle(item.severity);
                return (
                    <div
                        key={item.id}
                        role="alert"
                        className={cn("flex items-center gap-3 rounded-lg border px-4 py-3", style.container)}
                    >
                        <BellAlertIcon className={cn("size-5 shrink-0", style.accent)} />
                        <div className="min-w-0 flex-1">
                            <p className={cn("font-medium tracking-tight", style.accent)}>{content.title}</p>
                            {content.body ? (
                                <p className="mt-0.5 text-sm text-foreground">{content.body}</p>
                            ) : null}
                        </div>
                        <div className="flex shrink-0 items-center gap-2">
                            {item.type === "task.due" ? (
                                <Button size="sm" onClick={() => void finishTask(item)}>
                                    {t("completeTask")}
                                </Button>
                            ) : null}
                            <Button
                                variant="ghost"
                                size="icon-sm"
                                aria-label={t("dismiss")}
                                onClick={() => void dismiss(item)}
                            >
                                <XMarkIcon />
                            </Button>
                        </div>
                    </div>
                );
            })}
        </div>
    );
}
