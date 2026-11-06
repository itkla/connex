"use client";

import { ExclamationTriangleIcon } from "@heroicons/react/24/outline";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";

import { EmptyState } from "@/app/components/EmptyState";
import { PageHeader } from "@/app/components/PageHeader";
import { PageShell } from "@/app/components/PageShell";
import { safeNotificationUrl } from "@/app/components/notifications/notificationContent";
import { useNotificationWorkspaceActions } from "@/app/components/notifications/useNotificationWorkspaceActions";
import { markNotificationRead } from "@/app/lib/api";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

/** Resolves an emailed notification under recipient authorization before selecting its workspace. */
export default function NotificationEmailLink({ notificationId }: { notificationId: number | null }) {
    const t = useTranslations("Notifications");
    const { openNotification } = useNotificationWorkspaceActions();
    const [failed, setFailed] = useState(notificationId == null);

    useEffect(() => {
        if (notificationId == null) return;
        let active = true;
        void markNotificationRead(notificationId).then(async (notification) => {
            if (!active) return;
            if (!safeNotificationUrl(notification.actionUrl)) {
                setFailed(true);
                return;
            }
            const opened = await openNotification(notification);
            if (active && !opened) setFailed(true);
        }).catch(() => {
            if (active) setFailed(true);
        });
        return () => { active = false; };
    }, [notificationId, openNotification]);

    return (
        <PageShell>
            <PageHeader title={t("title")} />
            {failed ? (
                <EmptyState
                    icon={ExclamationTriangleIcon}
                    title={t("emailLinkError")}
                    body={t("emailLinkHelp")}
                    tone="muted"
                    action={<Button asChild><Link href="/notifications">{t("viewAll")}</Link></Button>}
                />
            ) : (
                <div role="status" className="space-y-3">
                    <span className="sr-only">{t("openingEmailLink")}</span>
                    <Skeleton className="h-6 w-2/3" />
                    <Skeleton className="h-4 w-1/2" />
                </div>
            )}
        </PageShell>
    );
}
