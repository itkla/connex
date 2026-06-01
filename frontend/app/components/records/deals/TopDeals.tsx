'use client';

import Link from 'next/link';
import { TrophyIcon } from '@heroicons/react/24/solid';
import { useLocale, useTranslations } from 'next-intl';

import { type Company, type Deal } from '@/app/lib/types';
import { formatCompactCurrency, parseMysqlDateTime } from '@/app/lib/utils';

const RANK_COLORS = ['#fbbf24', '#94a3b8', '#cd7f32'] as const; // gold, silver, bronze

function isClosed(deal: Deal): boolean {
    const t = parseMysqlDateTime(deal.closedAt);
    return Number.isFinite(t) && t <= Date.now();
}

type Field = 'value' | 'actualValue';

export default function TopDeals({
    deals,
    companyById,
}: {
    deals: Deal[];
    companyById: Map<number, Company>;
}) {
    const t = useTranslations('DealsTopDeals');
    const topOpen = [...deals]
        .filter((d) => !isClosed(d))
        .sort((a, b) => (b.value ?? 0) - (a.value ?? 0))
        .slice(0, 3);

    const topWins = [...deals]
        .filter(isClosed)
        .sort((a, b) => (b.actualValue ?? 0) - (a.actualValue ?? 0))
        .slice(0, 3);

    return (
        <div className="grid h-64 grid-rows-2 gap-3">
            <Section
                title={t('biggestOpenDeals')}
                deals={topOpen}
                field="value"
                companyById={companyById}
                emptyLabel={t('noOpenDeals')}
                rankLabels={[t('firstPlace'), t('secondPlace'), t('thirdPlace')]}
            />
            <Section
                title={t('topWins')}
                deals={topWins}
                field="actualValue"
                companyById={companyById}
                emptyLabel={t('noClosedDeals')}
                rankLabels={[t('firstPlace'), t('secondPlace'), t('thirdPlace')]}
            />
        </div>
    );
}

function Section({
    title,
    deals,
    field,
    companyById,
    emptyLabel,
    rankLabels,
}: {
    title: string;
    deals: Deal[];
    field: Field;
    companyById: Map<number, Company>;
    emptyLabel: string;
    rankLabels: readonly string[];
}) {
    const locale = useLocale();
    return (
        <div className="min-h-0 overflow-hidden">
            <h3 className="mb-1.5 text-xs uppercase tracking-wider text-neutral-500">{title}</h3>
            {deals.length === 0 ? (
                <p className="text-sm text-neutral-500">{emptyLabel}</p>
            ) : (
                <ul className="space-y-0.5">
                    {deals.map((d, i) => {
                        const companyName = d.company != null ? companyById.get(d.company)?.name : null;
                        return (
                            <li key={d.id}>
                                <Link
                                    href={`/records/deals/${d.id}`}
                                    className="flex items-center justify-between gap-2 rounded-md px-2 py-1 text-sm transition hover:bg-neutral-200"
                                >
                                    <span className="flex min-w-0 items-center gap-1.5">
                                        <TrophyIcon
                                            aria-label={rankLabels[i]}
                                            className="size-4 shrink-0"
                                            style={{ color: RANK_COLORS[i] }}
                                        />
                                        <span className="min-w-0 truncate">
                                            <span className="font-medium text-neutral-900">{d.name}</span>
                                            {companyName && (
                                                <span className="text-neutral-500"> · {companyName}</span>
                                            )}
                                        </span>
                                    </span>
                                    <span className="shrink-0 font-medium text-neutral-700">
                                        {formatCompactCurrency(d[field] ?? 0, d.currency || 'USD', locale)}
                                    </span>
                                </Link>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}