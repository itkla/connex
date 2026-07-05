'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowTrendingUpIcon, ArrowTrendingDownIcon, MinusIcon } from '@heroicons/react/16/solid';

import { type RelationshipTemperature, type TemperatureBand, type TemperatureTrend } from '@/app/lib/types';
import { WARMTH_BANDS, WARMTH_VAR, bandCounts, trendCounts } from '@/app/components/overview/analytics/relationshipMetrics';

const TREND_ICON: Record<TemperatureTrend, typeof ArrowTrendingUpIcon> = {
    rising: ArrowTrendingUpIcon,
    steady: MinusIcon,
    cooling: ArrowTrendingDownIcon,
};

const TREND_TONE: Record<TemperatureTrend, string> = {
    rising: 'text-warmth-hot',
    steady: 'text-muted-foreground',
    cooling: 'text-warmth-cold',
};

function StackedBar({ counts, total }: { counts: Record<TemperatureBand, number>; total: number }) {
    return (
        <div className="flex h-7 w-full overflow-hidden rounded-md bg-muted" role="presentation">
            {WARMTH_BANDS.map((band) =>
                counts[band] > 0 ? (
                    <div
                        key={band}
                        className="h-full transition-[width] duration-500 ease-out motion-reduce:transition-none"
                        style={{ width: `${(counts[band] / total) * 100}%`, backgroundColor: WARMTH_VAR[band] }}
                    />
                ) : null,
            )}
        </div>
    );
}

function EntityRow({
    label,
    counts,
}: {
    label: string;
    counts: Record<TemperatureBand, number>;
}) {
    const total = WARMTH_BANDS.reduce((sum, band) => sum + counts[band], 0);
    if (total === 0) return null;
    return (
        <div>
            <div className="mb-1.5 flex items-baseline justify-between gap-3">
                <span className="text-sm font-medium text-foreground">{label}</span>
                <span className="text-sm tabular-nums text-muted-foreground">{total}</span>
            </div>
            <StackedBar counts={counts} total={total} />
        </div>
    );
}

export default function WarmthDistribution({
    contacts,
    companies,
}: {
    contacts: RelationshipTemperature[];
    companies: RelationshipTemperature[];
}) {
    const t = useTranslations('AnalyticsWarmth');
    const tBand = useTranslations('Temperature');

    const contactCounts = useMemo(() => bandCounts(contacts), [contacts]);
    const companyCounts = useMemo(() => bandCounts(companies), [companies]);
    const trends = useMemo(() => trendCounts(contacts), [contacts]);

    const contactTotal = contacts.length;
    const companyTotal = companies.length;

    if (contactTotal === 0 && companyTotal === 0) {
        return (
            <div className="flex h-56 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>
        );
    }

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-muted-foreground">
                {WARMTH_BANDS.map((band) => (
                    <span key={band} className="flex items-center gap-1.5">
                        <span className="size-2 rounded-sm" style={{ backgroundColor: WARMTH_VAR[band] }} />
                        {tBand(band)}
                    </span>
                ))}
            </div>

            <div className="flex flex-col gap-5">
                <EntityRow label={t('contacts')} counts={contactCounts} />
                <EntityRow label={t('companies')} counts={companyCounts} />
            </div>

            <div className="mt-6 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border pt-4">
                <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('trendLabel')}
                </span>
                {(Object.keys(trends) as TemperatureTrend[]).map((trend) => {
                    const Icon = TREND_ICON[trend];
                    return (
                        <span key={trend} className="flex items-center gap-1.5 text-sm tabular-nums text-foreground">
                            <Icon className={`size-4 ${TREND_TONE[trend]}`} />
                            {trends[trend]}
                            <span className="text-muted-foreground">{t(`trend_${trend}`)}</span>
                        </span>
                    );
                })}
            </div>
        </div>
    );
}
