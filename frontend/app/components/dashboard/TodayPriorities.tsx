import Link from 'next/link';
import { getTranslations } from 'next-intl/server';
import { ArrowRightIcon, QueueListIcon } from '@heroicons/react/24/outline';

import type { CookieResult } from '@/app/lib/api';
import type { WorkItemSummary } from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';

/**
 * The bounded Home seam into My Work: a one-line count of the work waiting on the
 * member, linking to the `/me` queue. Counts only — rows, evidence, filters, and
 * actions live on My Work itself.
 *
 * <p>Honesty rules mirror the queue's: an incomplete or partial projection shows an
 * at-least count, an unavailable one says so instead of implying an all-clear, and a
 * fresh zero renders nothing because the greeting already carries the all-clear.
 */
export default async function TodayPriorities({
    summary,
}: {
    summary: CookieResult<WorkItemSummary>;
}) {
    const t = await getTranslations('DashboardToday');

    if (!summary.ok) {
        return (
            <Link
                href="/me"
                className="group flex items-center gap-3 rounded-2xl border border-border bg-card px-5 py-3.5 transition-colors hover:bg-muted/50"
            >
                <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground">
                    <QueueListIcon className="size-4" aria-hidden="true" />
                </span>
                <span className="min-w-0 flex-1 text-sm text-muted-foreground">{t('unavailable')}</span>
                <span className="flex shrink-0 items-center gap-1 text-sm font-medium text-foreground transition-colors group-hover:text-primary">
                    {t('viewMyWork')}
                    <ArrowRightIcon className="size-3.5" aria-hidden="true" />
                </span>
            </Link>
        );
    }

    const data = summary.data;
    const incomplete = !data.totalsComplete || data.availability !== 'available';
    if (data.availability === 'unavailable') {
        return (
            <Link
                href="/me"
                className="group flex items-center gap-3 rounded-2xl border border-border bg-card px-5 py-3.5 transition-colors hover:bg-muted/50"
            >
                <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground">
                    <QueueListIcon className="size-4" aria-hidden="true" />
                </span>
                <span className="min-w-0 flex-1 text-sm text-muted-foreground">{t('unavailable')}</span>
                <span className="flex shrink-0 items-center gap-1 text-sm font-medium text-foreground transition-colors group-hover:text-primary">
                    {t('viewMyWork')}
                    <ArrowRightIcon className="size-3.5" aria-hidden="true" />
                </span>
            </Link>
        );
    }
    if (data.knownTotal === 0 && !incomplete) {
        return null;
    }

    const countLabel = incomplete
        ? t('atLeast', { count: data.knownTotal })
        : t('myWorkCount', { count: data.knownTotal });

    return (
        <Link
            href="/me"
            className="group flex items-center gap-3 rounded-2xl border border-border bg-card px-5 py-3.5 transition-colors hover:bg-muted/50"
        >
            <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-brand-light/60 text-foreground">
                <QueueListIcon className="size-4" aria-hidden="true" />
            </span>
            <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-foreground">{countLabel}</span>
                {incomplete && (
                    <span className="mt-0.5 block truncate text-xs text-muted-foreground">{t('partial')}</span>
                )}
            </span>
            {data.knownCritical > 0 && (
                <Badge variant="outline" className="shrink-0 bg-risk-high/12 ring-risk-high/40">
                    <span className="size-1.5 rounded-full bg-risk-high" />
                    {t('myWorkCritical', { count: data.knownCritical })}
                </Badge>
            )}
            <span className="flex shrink-0 items-center gap-1 text-sm font-medium text-foreground transition-colors group-hover:text-primary">
                {t('viewMyWork')}
                <ArrowRightIcon className="size-3.5" aria-hidden="true" />
            </span>
        </Link>
    );
}
