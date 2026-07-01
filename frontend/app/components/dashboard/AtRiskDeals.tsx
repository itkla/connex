'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { BuildingOffice2Icon } from '@heroicons/react/24/outline';

import type { Company, Deal, DealRisk } from '@/app/lib/types';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import DealRiskPill from '@/app/components/records/deals/DealRiskPill';
import { useRiskText } from '@/app/components/records/deals/dealRisk';

export type AtRiskItem = { deal: Deal; risk: DealRisk; company?: Company };

/**
 * Dashboard widget: open deals the risk engine has flagged, highest risk first, each with its
 * top contributing factor and a risk pill. Purely informational — the row links to the deal.
 */
export default function AtRiskDeals({ items }: { items: AtRiskItem[] }) {
    const t = useTranslations('AtRiskDeals');
    const { factorText } = useRiskText();

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {items.length === 0 ? (
                <p className="flex-1 px-4 py-10 text-center text-sm text-muted-foreground">{t('empty')}</p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {items.map(({ deal, risk, company }) => (
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
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm font-medium text-foreground">{deal.name}</p>
                                    <p className="truncate text-xs text-muted-foreground">
                                        {risk.factors.length > 0
                                            ? factorText(risk.factors[0])
                                            : (company?.name ?? '')}
                                    </p>
                                </div>
                            </Link>
                            <DealRiskPill risk={risk} />
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
