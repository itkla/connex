'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';
import CurrencyControl from '@/app/components/overview/analytics/CurrencyControl';

/** Explains a single-currency view and offers the other authorized currency buckets. */
export default function DealCurrencyScope({ counts, currency, onChange, recordsHref, dashboard = false }: {
    counts: ReadonlyMap<string, number>;
    currency: string;
    onChange: (currency: string) => void;
    recordsHref?: string;
    dashboard?: boolean;
}) {
    const t = useTranslations('DealCurrencyScope');
    const excluded = [...counts].reduce((total, [code, count]) => total + (code === currency ? 0 : count), 0);
    const options = counts.has(currency) ? counts : new Map([[currency, 0], ...counts]);

    return (
        <section aria-label={t('title')} className="flex flex-wrap items-center justify-between gap-3 border-b border-border pb-4">
            <div className="min-w-0 space-y-1">
                <p className="text-sm font-medium">{t(dashboard ? 'dashboardBasis' : 'dealsBasis', { currency })}</p>
                <p className="text-xs text-muted-foreground">{t(dashboard ? 'dashboardExcluded' : 'excluded', { count: excluded })}</p>
            </div>
            <div className="flex flex-wrap items-center gap-3">
                <CurrencyControl counts={options} currency={currency} hasDeals onChange={onChange} />
                {recordsHref && (
                    <Link href={recordsHref} className="text-sm font-medium text-brand-text underline-offset-4 hover:underline">
                        {t('viewDeals', { currency })}
                    </Link>
                )}
            </div>
        </section>
    );
}
