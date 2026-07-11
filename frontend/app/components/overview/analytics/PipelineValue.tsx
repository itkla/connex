'use client';

import { useMemo } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';

import { type DealPipelineValue, type Pipeline } from '@/app/lib/types';
import { formatCompactCurrency } from '@/app/lib/utils';

const WON_COLOR = 'var(--color-brand)';
const OPEN_COLOR = 'color-mix(in oklch, var(--color-brand) 30%, var(--card))';

type Row = { id: number; name: string; won: number; open: number; openCount: number; total: number };

/**
 * Per-pipeline value breakdown (won-in-range vs open) from the server-computed
 * {@link DealPipelineValue} rollup. Pipeline names are joined from the loaded {@code pipelines}.
 */
export default function PipelineValue({
    values,
    pipelines,
    currency,
}: {
    values: DealPipelineValue[];
    pipelines: Pipeline[];
    currency: string;
}) {
    const t = useTranslations('AnalyticsPipelines');
    const locale = useLocale();

    const rows = useMemo<Row[]>(() => {
        const nameById = new Map(pipelines.map((p) => [p.id, p.name]));
        return values
            .filter((v): v is DealPipelineValue & { pipelineId: number } => v.pipelineId != null)
            .map((v) => ({
                id: v.pipelineId,
                name: nameById.get(v.pipelineId) ?? '',
                won: v.wonValue,
                open: v.openValue,
                openCount: v.openCount,
                total: v.wonValue + v.openValue,
            }))
            .filter((r) => r.name !== '' && r.total > 0)
            .sort((a, b) => b.total - a.total);
    }, [values, pipelines]);

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