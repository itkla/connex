'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import DealCurrencyScope from '@/app/components/records/deals/DealCurrencyScope';

/** Navigates the server-rendered dashboard so all monetary queries change currency together. */
export default function DashboardCurrencyScope({ counts, currency }: {
    counts: ReadonlyMap<string, number>;
    currency: string;
}) {
    const router = useRouter();
    const pathname = usePathname();
    const searchParams = useSearchParams();

    return (
        <DealCurrencyScope
            dashboard
            counts={counts}
            currency={currency}
            recordsHref={`/records/deals?${new URLSearchParams({ currency })}`}
            onChange={(nextCurrency) => {
                const params = new URLSearchParams(searchParams);
                params.set('currency', nextCurrency);
                router.push(`${pathname}?${params}`, { scroll: false });
            }}
        />
    );
}
