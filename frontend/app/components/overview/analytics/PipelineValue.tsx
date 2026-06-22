'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';

import { type Deal, type Pipeline } from '@/app/lib/types';
import { formatCompactCurrency, parseMysqlDateTime } from '@/app/lib/utils';
import { isClosed, RANGE_DAYS, type RangeKey } from '@/app/components/overview/analytics/metrics';

const WON_COLOR = 'var(--color-brand)';
const OPEN_COLOR = 'color-mix(in oklch, var(--color-brand) 30%, var(--card))';

type Row = { id: number; name: string; won: number; open: number; openCount: number; total: number };

export default function PipelineValue({
    deals,
    pipelines,
    range,
    currency,
}: {
    deals: Deal[];
    pipelines: Pipeline[];
    range: RangeKey;
    currency: string;
}) {
    const t = useTranslations('AnalyticsPipelines');
    const locale = useLocale();
    const [now] = useState(() => Date.now());

    const rows = useMemo<Row[]>(() => {
        const start = now - RANGE_DAYS[range] * 86400000; // 1 day in milliseconds
        const totals = new Map<number, { won: number; open: number; openCount: number }>();
        for (const deal of deals) {
            if (deal.pipeline == null) continue;
            const entry = totals.get(deal.pipeline) ?? { won: 0, open: 0, openCount: 0 };
            if (!isClosed(deal, now)) {
                entry.open += deal.value ?? 0;
                entry.openCount += 1;
            } else {
                const closed = parseMysqlDateTime(deal.closedAt);
                if (closed >= start && closed <= now && deal.won === true) {
                    entry.won += deal.actualValue ?? 0;
                }
            }
            totals.set(deal.pipeline, entry);
        }
        return pipelines
            .filter((p) => totals.has(p.id))
            .map((p) => {
                const entry = totals.get(p.id)!;
                return {
                    id: p.id,
                    name: p.name,
                    won: entry.won,
                    open: entry.open,
                    openCount: entry.openCount,
                    total: entry.won + entry.open,
                };
            })
            // sort by total descending
            .filter((r) => r.total > 0)
            .sort((a, b) => b.total - a.total);
    }, [deals, pipelines, range, now]);

    if (rows.length === 0) {
        return <div className="flex h-40 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    const maxTotal = Math.max(...rows.map((r) => r.total), 1);

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5 flex items-center gap-4 text-xs text-muted-foreground">
                <span className="flex items-center gap-1.5">
                    <span className="size-2 rounded-sm" style={{ backgroundColor: WON_COLOR }} />
                    {t('won')}
                </span>
                <span className="flex items-center gap-1.5">
                    <span className="size-2 rounded-sm" style={{ backgroundColor: OPEN_COLOR }} />
                    {t('open')}
                </span>
            </div>
            <ul className="flex flex-col gap-5">
                {rows.map((row, i) => (
                    <li key={row.id}>
                        <Link
                            href="/records/pipelines"
                            className="group block -mx-2 rounded-lg px-2 py-2 transition hover:bg-muted"
                        >
                        <div className="mb-1.5 flex items-baseline justify-between gap-3">
                            <span className="flex min-w-0 items-center gap-2">
                                <span className="w-4 shrink-0 text-sm tabular-nums text-muted-foreground">{i + 1}</span>
                                <span className="min-w-0 truncate text-sm font-medium text-foreground">{row.name}</span>
                            </span>
                            <span className="shrink-0 text-sm font-semibold tabular-nums text-foreground">
                                {formatCompactCurrency(row.total, currency, locale)}
                            </span>
                        </div>
                        <div className="flex h-7 w-full overflow-hidden rounded-md bg-muted">
                            <div
                                className="h-full transition-[width] duration-500 ease-out motion-reduce:transition-none"
                                style={{ width: `${(row.won / maxTotal) * 100}%`, backgroundColor: WON_COLOR }}
                            />
                            <div
                                className="h-full transition-[width] duration-500 ease-out motion-reduce:transition-none"
                                style={{ width: `${(row.open / maxTotal) * 100}%`, backgroundColor: OPEN_COLOR }}
                            />
                        </div>
                        <div className="mt-1.5 flex items-center gap-2 text-xs tabular-nums text-muted-foreground">
                            <span>
                                {t('rowSummary', {
                                    won: formatCompactCurrency(row.won, currency, locale),
                                    open: formatCompactCurrency(row.open, currency, locale),
                                })}
                            </span>
                            <span className="text-border">·</span>
                            <span>{t('deals', { count: row.openCount })}</span>
                        </div>
                        </Link>
                    </li>
                ))}
            </ul>
        </div>
    );
}