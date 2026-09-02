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
 * <p>Honesty rules mirror the queue's: an incomplete projection qualifies both the
 * total and the critical count with at-least language, an unavailable one says so
 * instead of implying an all-clear, and a fresh zero renders a named confirmation
 * rather than vanishing — My Work is actor-scoped, so no other Home section can
 * truthfully carry its all-clear.
 */
export default async function TodayPriorities({
    summary,
}: {
    summary: CookieResult<WorkItemSummary>;
}) {
    const t = await getTranslations('DashboardToday');

    if (!summary.ok || summary.data.availability === 'unavailable') {
        return (
            <Strip
                muted
                label={t('unavailable')}
                cta={t('viewMyWork')}
            />
        );
    }

    const data = summary.data;
    const total = Math.max(0, data.knownTotal);
    const critical = Math.min(Math.max(0, data.knownCritical), Math.max(total, data.knownCritical));
    const incomplete = !data.totalsComplete || data.availability !== 'available';

    if (total === 0 && !incomplete) {
        return (
            <Strip
                muted
                label={t('allClear')}
                cta={t('viewMyWork')}
            />
        );
    }

    return (
        <Strip
            label={incomplete ? t('atLeast', { count: total }) : t('myWorkCount', { count: total })}
            note={incomplete ? t('partial') : undefined}
            badge={critical > 0
                ? (incomplete
                    ? t('myWorkCriticalAtLeast', { count: critical })
                    : t('myWorkCritical', { count: critical }))
                : undefined}
            cta={t('viewMyWork')}
        />
    );
}

function Strip({
    label,
    note,
    badge,
    cta,
    muted = false,
}: {
    label: string;
    note?: string;
    badge?: string;
    cta: string;
    muted?: boolean;
}) {
    return (
        <div className="overflow-hidden rounded-2xl border border-border bg-card">
            <Link
                href="/me"
                aria-label={cta}
                className="group flex items-center gap-3 px-5 py-3.5 transition-colors hover:bg-muted/50"
            >
                <span className={`grid size-9 shrink-0 place-items-center rounded-lg ${muted ? 'bg-muted text-muted-foreground' : 'bg-brand-light/60 text-foreground'}`}>
                    <QueueListIcon className="size-4" aria-hidden="true" />
                </span>
                <span className="min-w-0 flex-1">
                    <span className={`block truncate text-sm ${muted ? 'text-muted-foreground' : 'font-medium text-foreground'}`}>
                        {label}
                    </span>
                    {note && (
                        <span className="mt-0.5 block truncate text-xs text-muted-foreground">{note}</span>
                    )}
                </span>
                {badge && (
                    <Badge variant="outline" className="shrink-0 bg-risk-high/12 ring-risk-high/40">
                        <span className="size-1.5 rounded-full bg-risk-high" />
                        {badge}
                    </Badge>
                )}
                <span className="flex shrink-0 items-center gap-1 text-sm font-medium text-foreground transition-colors group-hover:text-primary">
                    <span className="hidden sm:inline">{cta}</span>
                    <ArrowRightIcon className="size-3.5" aria-hidden="true" />
                </span>
            </Link>
        </div>
    );
}
