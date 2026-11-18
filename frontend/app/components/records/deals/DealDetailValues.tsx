'use client';

import { useLocale, useTimeZone, useTranslations } from 'next-intl';
import { CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/solid';
import InfoRow from '@/app/components/me/InfoRow';
import SummaryTile from '@/app/components/SummaryTile';
import type { Stage } from '@/app/lib/types';
import { formatCompactCurrency, formatCurrency, formatDate } from '@/app/lib/utils';
import { useDealDetailRecord } from './DealDetailState';
import { dealOutcome, isDealClosed } from './dealOutcome';
import { formatDealClosedAt } from './dealDates';

/** Renders the same current stage in the identity header and profile. */
export function DealDetailStage({ stages, row = false }: { stages: Stage[]; row?: boolean }) {
    const deal = useDealDetailRecord();
    const t = useTranslations('DealsPage');
    const stage = stages.find((item) => item.id === deal.stage);
    return row ? <InfoRow label={t('stage')} value={stage?.name ?? '—'} /> : stage ? <> · {stage.name}</> : null;
}

/** Renders the acknowledged outcome without waiting for a refreshed server payload. */
export function DealDetailStatus() {
    const outcome = dealOutcome(useDealDetailRecord().won);
    const t = useTranslations('DealsPage');
    if (outcome === 'won') return (
        <span className="inline-flex items-center gap-1 rounded-full bg-brand-light px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-text">
            <CheckCircleIcon className="size-3" /> {t('statusWon')}
        </span>
    );
    if (outcome === 'lost') return (
        <span className="inline-flex items-center gap-1 rounded-full bg-destructive/10 px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-destructive">
            <XCircleIcon className="size-3" /> {t('statusLost')}
        </span>
    );
    return <span className="rounded-full bg-brand px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-foreground">{t('statusOpen')}</span>;
}

/** Keeps the header's amount and projected/actual label on one snapshot. */
export function DealDetailAmount() {
    const deal = useDealDetailRecord();
    const t = useTranslations('DealsPage');
    const locale = useLocale();
    const closed = isDealClosed(deal);
    const currency = deal.currency || 'USD';
    return <div className="flex flex-col items-end gap-1">
        <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
            {closed ? t('actual') : t('projected')} · {currency}
        </span>
        <div className="text-3xl font-extrabold tracking-tight text-foreground tabular-nums">
            {formatCurrency(closed ? deal.actualValue : deal.value, currency, locale)}
        </div>
    </div>;
}

/** Shows the acknowledged close date on the workspace reporting calendar. */
export function DealDetailClosedAt({ timezone }: { timezone: string }) {
    const deal = useDealDetailRecord();
    const t = useTranslations('DealsPage');
    const locale = useLocale();
    return <InfoRow label={t('closedAt')} value={isDealClosed(deal) ? formatDealClosedAt(deal.closedAt, locale, timezone) : '—'} />;
}

/** Derives every performance tile from the same acknowledged deal as the header. */
export function DealDetailPerformance({ timezone }: { timezone: string }) {
    const deal = useDealDetailRecord();
    const t = useTranslations('DealsPage');
    const locale = useLocale();
    const timeZone = useTimeZone();
    const closed = isDealClosed(deal);
    const currency = deal.currency || 'USD';
    const variance = closed && deal.value > 0 ? (deal.actualValue - deal.value) / deal.value : null;
    return <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <SummaryTile label={t('projectedValue')} value={formatCompactCurrency(deal.value, currency, locale)} />
        <SummaryTile label={t('actualValue')} value={closed ? formatCompactCurrency(deal.actualValue, currency, locale) : '—'} />
        <SummaryTile label={t('variance')} value={variance != null ? `${variance >= 0 ? '+' : ''}${(variance * 100).toFixed(1)}%` : '—'} />
        <SummaryTile label={closed ? t('closed') : t('expectedClose')} value={closed ? formatDealClosedAt(deal.closedAt, locale, timezone) : formatDate(deal.expectedCloseDate, locale, timeZone)} />
    </div>;
}
