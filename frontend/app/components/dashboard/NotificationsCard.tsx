'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';

import type { Notification } from '@/app/lib/types';
import { formatRelativeTime } from '@/app/lib/utils';
import {
    notificationContent,
    notificationIcon,
    notificationSeverityStyle,
    safeNotificationUrl,
} from '@/app/components/notifications/notificationContent';
import { cn } from '@/lib/utils';

export type NotificationsCardProps = { items: Notification[] };

/**
 * Dashboard widget: the most recent unread notifications, newest first. Each row shows the
 * severity-colored entity icon, the localized title/body, and a relative timestamp; rows with a
 * safe action URL link to the underlying record. Purely informational.
 */
export default function NotificationsCard({ items }: NotificationsCardProps) {
    const t = useTranslations('Notifications');
    const locale = useLocale();

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
                                        className="flex min-w-0 flex-1 items-center gap-3 transition-opacity hover:opacity-80"
                                    >
                                        {inner}
                                    </Link>
                                ) : (
                                    <div className="flex min-w-0 flex-1 items-center gap-3">{inner}</div>
                                )}
                                <time className="shrink-0 text-xs text-muted-foreground">
                                    {formatRelativeTime(item.triggeredAt, locale)}
                                </time>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}
