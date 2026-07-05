'use client';

import { useLocale, useTranslations } from 'next-intl';

import { formatCompactCurrency } from '@/app/lib/utils';
import InfoTip from '@/app/components/overview/analytics/InfoTip';

export type RelationshipKpiData = {
    tracked: number;
    warmShare: number;
    cooling: number;
    pipelineAtRisk: number;
    atRiskDeals: number;
    introOpportunities: number;
};

type Tile = {
    key: 'warmShare' | 'cooling' | 'pipelineAtRisk' | 'introOpportunities';
    value: string;
    caption: string;
};

/**
 * Snapshot stat cluster for the relationship-intelligence section — the warmth, decay, deal-risk,
 * and intro signals as of now. Mirrors {@link KpiCluster}'s seamless tile grid but omits the
 * trend sparkline/delta, since these figures are a point-in-time read rather than a period total.
 */
export default function RelationshipKpis({
    data,
    currency,
}: {
    data: RelationshipKpiData;
    currency: string;
}) {
    const t = useTranslations('AnalyticsRelationshipKpis');
    const locale = useLocale();

    const tiles: Tile[] = [
        {
            key: 'warmShare',
            value: `${Math.round(data.warmShare * 100)}%`,
            caption: t('warmShareCaption', { count: data.tracked }),
        },
        {
            key: 'cooling',
            value: data.cooling.toLocaleString(locale),
            caption: t('coolingCaption'),
        },
        {
            key: 'pipelineAtRisk',
            value: formatCompactCurrency(data.pipelineAtRisk, currency, locale),
            caption: t('pipelineAtRiskCaption', { count: data.atRiskDeals }),
        },
        {
            key: 'introOpportunities',
            value: data.introOpportunities.toLocaleString(locale),
            caption: t('introOpportunitiesCaption'),
        },
    ];

    return (
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-2 lg:grid-cols-4">
            {tiles.map((tile) => (
                <div key={tile.key} className="flex flex-col gap-3 bg-card p-6">
                    <div className="flex items-center gap-1.5">
                        <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t(tile.key)}
                        </span>
                        <InfoTip title={t(tile.key)} label={t('infoAria')} body={t(`${tile.key}Tooltip`)} />
                    </div>
                    <span className="text-3xl leading-none text-foreground tabular-nums">{tile.value}</span>
                    <span className="text-xs text-muted-foreground">{tile.caption}</span>
                </div>
            ))}
        </div>
    );
}
