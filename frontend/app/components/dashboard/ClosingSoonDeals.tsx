'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { BuildingOffice2Icon } from '@heroicons/react/24/outline';

import type { Company, Deal } from '@/app/lib/types';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { formatShortDate } from '@/app/lib/utils';

export type ClosingSoonItem = { deal: Deal; company?: Company };

/**
 * Dashboard widget: open deals whose expected close date falls within the next week, each
 * with its company avatar and a compact close date. Purely informational — the row links to
 * the deal.
 */
export default function ClosingSoonDeals({ items }: { items: ClosingSoonItem[] }) {
    const t = useTranslations('ClosingSoonDeals');
    const locale = useLocale();

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {items.length === 0 ? (
                <p className="flex flex-1 items-center justify-center px-4 py-10 text-center text-sm text-muted-foreground">
                    {t('empty')}
                </p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {items.map(({ deal, company }) => (
                        <li key={deal.id} className="flex items-center gap-3 px-4 py-2.5">
                            <Link
                                href={`/records/deals/${deal.id}`}
                                className="flex min-w-0 flex-1 items-center gap-3 transition-opacity hover:opacity-80"
                            >
                                {company ? (
                                    <CompanyAvatar company={company} type="small" />
                                ) : (
                                    <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border">
                                        <BuildingOffice2Icon className="size-4" />
                                    </div>
                                )}
                                <p className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{deal.name}</p>
                            </Link>
                            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                {formatShortDate(deal.expectedCloseDate, locale)}
                            </span>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
