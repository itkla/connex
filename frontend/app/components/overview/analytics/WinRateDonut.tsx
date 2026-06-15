'use client';

import { useMemo, useState } from 'react';
import { Pie, PieChart, ResponsiveContainer, Tooltip as RechartsTooltip } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';

import { type Deal } from '@/app/lib/types';
import { type StageClass } from '@/app/components/records/deals/dealOutcome';
import { formatCompactCurrency, parseMysqlDateTime } from '@/app/lib/utils';
import { classOf, RANGE_DAYS, type RangeKey } from '@/app/components/overview/analytics/metrics';

const WON_COLOR = 'var(--chart-won)';
const LOST_COLOR = 'var(--chart-lost)';

type Slice = { key: 'won' | 'lost'; label: string; count: number; value: number; fill: string };

export default function WinRateDonut({
    deals,
    classById,
    range,
    currency,
}: {
    deals: Deal[];
    classById: Map<number, StageClass>;
    range: RangeKey;
    currency: string;
}) {
    const t = useTranslations('AnalyticsWinRate');
    const locale = useLocale();
    const [now] = useState(() => Date.now());

    const { won, lost } = useMemo(() => {
        const start = now - RANGE_DAYS[range] * 86400000; // 1 day in milliseconds
        let wonCount = 0;
        let wonValue = 0;
        let lostCount = 0;
        let lostValue = 0;
        for (const deal of deals) {
            const closed = parseMysqlDateTime(deal.closedAt);
            if (!Number.isFinite(closed) || closed > now || closed < start) continue;
            const cls = classOf(deal.stage, classById);
            if (cls === 'won') {
                wonCount += 1;
                wonValue += deal.actualValue ?? 0;
            } else if (cls === 'lost') {
                lostCount += 1;
                lostValue += deal.value ?? 0;
            }
        }
        return {
            won: { count: wonCount, value: wonValue },
            lost: { count: lostCount, value: lostValue },
        };
    }, [deals, classById, range, now]);

    const total = won.count + lost.count;
    if (total === 0) {
        return <div className="flex h-56 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    const rate = Math.round((won.count / total) * 100);
    const data: Slice[] = [
        { key: 'won', label: t('won'), count: won.count, value: won.value, fill: WON_COLOR },
        { key: 'lost', label: t('lost'), count: lost.count, value: lost.value, fill: LOST_COLOR },
    ];

    return (
        <div className="flex h-full flex-col items-center justify-center gap-5 sm:flex-row sm:gap-7">
            <div className="relative h-40 w-40 shrink-0">
                <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                        <Pie
                            data={data}
                            dataKey="count"
                            nameKey="label"
                            innerRadius={52}
                            outerRadius={72}
                            startAngle={90}
                            endAngle={-270}
                            stroke="var(--chart-stroke)"
                            strokeWidth={2}
                            isAnimationActive={false}
                        />
                        <RechartsTooltip content={<DonutTooltip currency={currency} />} />
                    </PieChart>
                </ResponsiveContainer>
                <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                    <span className="text-3xl font-semibold tabular-nums text-foreground">{rate}%</span>
                    <span className="text-[10px] uppercase tracking-[0.12em] text-muted-foreground">{t('rate')}</span>
                </div>
            </div>
            <ul className="w-full max-w-[14rem] space-y-3">
                {data.map((d) => (
                    <li key={d.key} className="flex items-center gap-3">
                        <span className="size-2.5 shrink-0 rounded-sm" style={{ backgroundColor: d.fill }} />
                        <span className="flex-1 text-sm text-muted-foreground">{d.label}</span>
                        <span className="text-right text-sm tabular-nums text-foreground">
                            {t('deals', { count: d.count })}
                            <span className="ml-2 text-muted-foreground">
                                {formatCompactCurrency(d.value, currency, locale)}
                            </span>
                        </span>
                    </li>
                ))}
            </ul>
        </div>
    );
}

function DonutTooltip({
    active,
    payload,
    currency,
}: {
    active?: boolean;
    payload?: Array<{ payload: Slice }>;
    currency: string;
}) {
    const locale = useLocale();
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    return (
        <div className="rounded-md bg-popover p-2 text-xs text-popover-foreground border border-border shadow-md">
            <div className="flex items-center gap-1.5 font-medium text-foreground">
                <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: d.fill }} />
                {d.label}
            </div>
            <div className="mt-1 text-muted-foreground tabular-nums">
                {d.count} · {formatCompactCurrency(d.value, currency, locale)}
            </div>
        </div>
    );
}