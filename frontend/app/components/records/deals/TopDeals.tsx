'use client';

import Link from 'next/link';
import { TrophyIcon } from '@heroicons/react/24/solid';
import { useLocale, useTranslations } from 'next-intl';

import { type DealSummary, type DealTop } from '@/app/lib/types';
import { formatCompactCurrency } from '@/app/lib/utils';

const RANK_COLORS = ['#fbbf24', '#94a3b8', '#cd7f32'] as const; // gold, silver, bronze

/**
 * Biggest open deals and top wins from the server-computed {@link DealTop} rollup. Company names
 * are pre-resolved on each {@link DealSummary}; no client-side deal aggregation.
 */
export default function TopDeals({ data }: { data: DealTop }) {
    const t = useTranslations('DealsTopDeals');

    return (
        <div className="grid h-64 grid-rows-2 gap-3">
            <Section
                title={t('biggestOpenDeals')}
                deals={data.topOpen}
                emptyLabel={t('noOpenDeals')}
                rankLabels={[t('firstPlace'), t('secondPlace'), t('thirdPlace')]}
            />
            <Section
                title={t('topWins')}
                deals={data.topWon}
                emptyLabel={t('noClosedDeals')}
                rankLabels={[t('firstPlace'), t('secondPlace'), t('thirdPlace')]}
            />
        </div>
    );
}

function Section({
    title,
    deals,
    emptyLabel,
    rankLabels,
}: {
    title: string;
    deals: DealSummary[];
    emptyLabel: string;
    rankLabels: readonly string[];
}) {
    const locale = useLocale();
    return (
        <div className="min-h-0 overflow-hidden">
            <h3 className="mb-1.5 text-xs uppercase tracking-wider text-muted-foreground">{title}</h3>
            {deals.length === 0 ? (
                <p className="text-sm text-muted-foreground">{emptyLabel}</p>
            ) : (
                <ul className="space-y-0.5">
                    {deals.map((d, i) => (
                        <li key={d.id}>
                            <Link
                                href={`/records/deals/${d.id}`}
                                className="flex items-center justify-between gap-2 rounded-md px-2 py-1 text-sm transition hover:bg-muted"
                            >
                                <span className="flex min-w-0 items-center gap-1.5">
                                    <TrophyIcon
                                        aria-label={rankLabels[i]}
                                        className="size-4 shrink-0"
                                        style={{ color: RANK_COLORS[i] }}
                                    />
                                    <span className="min-w-0 truncate">
                                        <span className="font-medium text-foreground">{d.name}</span>
                                        {d.companyName && (
                                            <span className="text-muted-foreground"> · {d.companyName}</span>
                                        )}
                                    </span>
                                </span>
                                <span className="shrink-0 font-medium text-foreground">
                                    {formatCompactCurrency(d.value ?? 0, d.currency || 'USD', locale)}
                                </span>
                            </Link>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
