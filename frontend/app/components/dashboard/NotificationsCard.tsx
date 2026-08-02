'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { useCallback, useEffect, useState } from 'react';

import { useNotificationWorkspaceActions } from '@/app/components/notifications/useNotificationWorkspaceActions';
import { getNotifications } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import type { Notification } from '@/app/lib/types';
import { useLiveNow } from '@/app/hooks/useNow';
import { formatRelativeTime } from '@/app/lib/utils';
import {
    notificationContent,
    notificationIcon,
    notificationSeverityStyle,
    safeNotificationUrl,
} from '@/app/components/notifications/notificationContent';
import { cn } from '@/lib/utils';
import { onNotificationStateChanged } from '@/app/components/notifications/notificationEvents';

export type NotificationsCardProps = {
    items: Notification[];
    recipientId: number;
    initialStateVersion: number;
};

type ReconciledNotifications = {
    sourceItems: Notification[];
    sourceStateVersion: number;
    items: Notification[];
};

/** Keeps the dashboard notification snapshot reconciled with recipient-wide state changes. */
export default function NotificationsCard({
    items,
    recipientId,
    initialStateVersion,
}: NotificationsCardProps) {
    const t = useTranslations('Notifications');
    const { openNotification } = useNotificationWorkspaceActions();
    const [reconciled, setReconciled] = useState<ReconciledNotifications | null>(null);
    const visibleItems = reconciled?.sourceItems === items
        && reconciled.sourceStateVersion === initialStateVersion
        ? reconciled.items
        : items;

    useEffect(() => {
        let active = true;
        let generation = 0;
        let retryId: number | null = null;
        let reconciledVersion = initialStateVersion;
        const reconcile = async (requiredVersion: number, canRetry: boolean, forceRefresh = false) => {
            if (!forceRefresh && requiredVersion <= reconciledVersion) return;
            const requestGeneration = ++generation;
            try {
                const page = await getNotifications({ status: 'unread', page: 1, size: 6 });
                if (!active || requestGeneration !== generation) return;
                if (page.stateVersion < requiredVersion && canRetry) {
                    retryId = window.setTimeout(
                        () => void reconcile(requiredVersion, false, forceRefresh),
                        250,
                    );
                    return;
                }
                if (page.stateVersion < requiredVersion) return;
                reconciledVersion = page.stateVersion;
                setReconciled({
                    sourceItems: items,
                    sourceStateVersion: initialStateVersion,
                    items: page.items,
                });
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
    }, [initialStateVersion, items, recipientId]);

    const handleOpen = useCallback((notification: Notification) => {
        void openNotification(notification)
            .then((opened) => {
                if (!opened) toastError(t('actionError'));
            })
            .catch(() => toastError(t('actionError')));
    }, [openNotification, t]);

    return <NotificationsCardView items={visibleItems} onOpen={handleOpen} />;
}

/**
 * Presentational dashboard widget for the most recent unread notifications, newest first.
 * @param items the notifications to render
 * @returns the notification card
 */
export function NotificationsCardView({
    items,
    onOpen,
}: {
    items: Notification[];
    onOpen?: (notification: Notification) => void;
}) {
    const t = useTranslations('Notifications');
    const locale = useLocale();
    const now = useLiveNow();

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {items.length === 0 ? (
                <p className="flex flex-1 items-center justify-center px-4 py-10 text-center text-sm text-muted-foreground">
                    {t('empty')}
                </p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {items.map((item) => {
                        const content = notificationContent(item, t, locale);
                        const Icon = notificationIcon(item);
                        const style = notificationSeverityStyle(item.severity);
                        const url = safeNotificationUrl(item.actionUrl);
                        const inner = (
                            <>
                                <span
                                    className={cn(
                                        'flex size-8 shrink-0 items-center justify-center rounded-full',
                                        style.chip,
                                    )}
                                >
                                    <Icon className="size-4" />
                                </span>
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm font-medium text-foreground">{content.title}</p>
                                    <p className="truncate text-xs text-muted-foreground">{content.body}</p>
                                </div>
                            </>
                        );
                        return (
                            <li key={item.id} className="flex items-center gap-3 px-4 py-2.5">
                                {url ? (
                                    <Link
                                        href={url}
                                        prefetch={onOpen ? false : undefined}
                                        onClick={(event) => {
                                            if (!onOpen) return;
                                            event.preventDefault();
                                            onOpen(item);
                                        }}
                                        className="flex min-w-0 flex-1 items-center gap-3 transition-opacity hover:opacity-80"
                                    >
                                        {inner}
                                    </Link>
                                ) : (
                                    <div className="flex min-w-0 flex-1 items-center gap-3">{inner}</div>
                                )}
                                <time className="shrink-0 text-xs text-muted-foreground">
                                    {formatRelativeTime(item.triggeredAt, locale, now)}
                                </time>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}
