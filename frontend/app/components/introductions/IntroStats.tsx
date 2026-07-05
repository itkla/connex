'use client';

import { useTranslations } from 'next-intl';

import CountUp from '@/app/components/dashboard/CountUp';

type StatTile = {
    key: string;
    label: string;
    hint: string;
    value: number;
    unavailable?: boolean;
};

/**
 * Overview strip for the Introductions page: how many intros are worth making, how many of those are
 * strong matches worth prioritizing, and how many the team has already made. Mirrors the analytics
 * KPI tile treatment. When the suggestion queue failed to load ({@code queueUnavailable}), the two
 * queue-derived tiles show an em dash instead of claiming zero.
 */
export default function IntroStats({
    opportunities,
    strong,
    made,
    queueUnavailable = false,
}: {
    opportunities: number;
    strong: number;
    made: number;
    queueUnavailable?: boolean;
}) {
    const t = useTranslations('Introductions');
    const tiles: StatTile[] = [
        {
            key: 'opportunities',
            label: t('statOpportunities'),
            hint: t('statOpportunitiesHint'),
            value: opportunities,
            unavailable: queueUnavailable,
        },
        {
            key: 'strong',
            label: t('statStrong'),
            hint: t('statStrongHint'),
            value: strong,
            unavailable: queueUnavailable,
        },
        { key: 'made', label: t('statMade'), hint: t('statMadeHint'), value: made },
    ];

    return (
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-3">
            {tiles.map((tile) => (
                <div key={tile.key} className="flex flex-col gap-1.5 bg-card p-5 sm:p-6">
                    <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                        {tile.label}
                    </span>
                    {tile.unavailable ? (
                        <span className="text-3xl leading-none text-muted-foreground tabular-nums">—</span>
                    ) : (
                        <CountUp value={tile.value} className="text-3xl leading-none text-foreground tabular-nums" />
                    )}
                    <span className="text-xs text-muted-foreground">{tile.hint}</span>
                </div>
            ))}
        </div>
    );
}
