"use client";

import { BellAlertIcon, XMarkIcon } from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";

import { completeTask, dismissNotification } from "@/app/lib/api";
import { type Notification } from "@/app/lib/types";
import { toastError } from "@/app/lib/toast";
import { useNotifications } from "@/app/hooks/useNotifications";
import { notificationContent, notificationSeverityStyle } from "@/app/components/notifications/notificationContent";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export default function EntityNotificationBanner({
    initialNotifications,
}: {
    initialNotifications: Notification[];
}) {
    const t = useTranslations("Notifications");
    const locale = useLocale();
    const { refreshUnread } = useNotifications();
    const [items, setItems] = useState(initialNotifications);

    async function dismiss(item: Notification) {
        try {
            await dismissNotification(item.id);
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
