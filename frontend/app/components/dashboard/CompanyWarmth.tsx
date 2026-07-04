import Link from 'next/link';
import { useTranslations } from 'next-intl';

import type { Company, RelationshipTemperature } from '@/app/lib/types';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import TemperaturePill from '@/app/components/records/TemperaturePill';

export type CompanyWarmthItem = { company: Company; temp: RelationshipTemperature };

/**
 * Dashboard widget: companies whose overall relationship is cooling, coolest first, each with its
 * warmth pill. Purely informational — the row links to the company record; no actions.
 */
export default function CompanyWarmth({ items }: { items: CompanyWarmthItem[] }) {
    const t = useTranslations('CompanyWarmth');

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {items.length === 0 ? (
                <p className="flex flex-1 items-center justify-center px-4 py-10 text-center text-sm text-muted-foreground">
                    {t('empty')}
                </p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {items.map(({ company, temp }) => (
                        <li key={company.id} className="flex items-center gap-3 px-4 py-2.5">
                            <Link
                                href={`/records/companies/${company.id}`}
                                className="flex min-w-0 flex-1 items-center gap-3 transition-opacity hover:opacity-80"
                            >
                                <CompanyAvatar company={company} type="small" />
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm font-medium text-foreground">{company.name}</p>
                                    {company.industry ? (
                                        <p className="truncate text-xs text-muted-foreground">{company.industry}</p>
                                    ) : null}
                                </div>
                            </Link>
                            <TemperaturePill temp={temp} />
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
