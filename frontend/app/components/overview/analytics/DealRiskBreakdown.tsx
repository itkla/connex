'use client';

import { useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';

import { type DealRisk, type DealRiskFactorCode } from '@/app/lib/types';
import { formatCompactCurrency } from '@/app/lib/utils';
import { useRiskText } from '@/app/components/records/deals/dealRisk';
import {
    RISK_LEVELS,
    RISK_VAR,
    riskFactorCounts,
    riskLevelCounts,
} from '@/app/components/overview/analytics/relationshipMetrics';

const FACTOR_LABEL_KEY: Record<DealRiskFactorCode, string> = {
    close_overdue: 'factor_close_overdue',
    closing_soon_quiet: 'factor_closing_soon_quiet',
    stalled: 'factor_stalled',
    stakeholder_cold: 'factor_stakeholder_cold',
    no_stakeholders: 'factor_no_stakeholders',
};

export default function DealRiskBreakdown({
    risks,
    pipelineAtRisk,
    atRiskDeals,
    currency,
}: {
    risks: DealRisk[];
    pipelineAtRisk: number;
    atRiskDeals: number;
    currency: string;
}) {
    const t = useTranslations('AnalyticsDealRisk');
    const { levelLabel } = useRiskText();
    const locale = useLocale();

    const levels = useMemo(() => riskLevelCounts(risks), [risks]);
    const factors = useMemo(() => riskFactorCounts(risks).slice(0, 5), [risks]);

    if (atRiskDeals === 0) {
        return (
            <div className="flex h-56 items-center justify-center text-center text-sm text-muted-foreground">
                {t('empty')}
            </div>
        );
    }

    const maxLevel = Math.max(...RISK_LEVELS.map((level) => levels[level]), 1);
    const maxFactor = Math.max(...factors.map((factor) => factor.count), 1);

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5">
                <div className="text-3xl leading-none text-foreground tabular-nums">
                    {formatCompactCurrency(pipelineAtRisk, currency, locale)}
                </div>
                <p className="mt-1.5 text-sm text-muted-foreground">{t('summary', { count: atRiskDeals })}</p>
            </div>

            <ul className="flex flex-col gap-3">
                {RISK_LEVELS.map((level) => {
                    const count = levels[level];
                    if (count === 0) return null;
                    return (
                        <li key={level}>
                            <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
                                <span className="text-foreground">{levelLabel(level)}</span>
                                <span className="tabular-nums text-muted-foreground">{count}</span>
                            </div>
                            <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                                <div
                                    className="h-full rounded-full transition-[width] duration-500 ease-out motion-reduce:transition-none"
                                    style={{ width: `${Math.max(6, (count / maxLevel) * 100)}%`, backgroundColor: RISK_VAR[level] }}
                                />
                            </div>
                        </li>
                    );
                })}
            </ul>

            {factors.length > 0 && (
                <div className="mt-6 border-t border-border pt-4">
                    <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t('factorsLabel')}
                    </span>
                    <ul className="mt-3 flex flex-col gap-2.5">
                        {factors.map((factor) => (
                            <li key={factor.code} className="flex items-center gap-3">
                                <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                                    {t(FACTOR_LABEL_KEY[factor.code])}
                                </span>
                                <div className="h-1.5 w-20 shrink-0 overflow-hidden rounded-full bg-muted">
                                    <div
                                        className="h-full rounded-full bg-muted-foreground/50 transition-[width] duration-500 ease-out motion-reduce:transition-none"
                                        style={{ width: `${(factor.count / maxFactor) * 100}%` }}
                                    />
                                </div>
                                <span className="w-6 shrink-0 text-right text-sm tabular-nums text-muted-foreground">
                                    {factor.count}
                                </span>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </div>
    );
}
