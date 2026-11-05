'use client';

import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

/** Shared Analytics currency picker, ordered by deal count and hidden when there are no deals. */
export default function CurrencyControl({
    counts,
    currency,
    hasDeals,
    onChange,
}: {
    counts: ReadonlyMap<string, number>;
    currency: string;
    hasDeals: boolean;
    onChange: (currency: string) => void;
}) {
    const t = useTranslations('AnalyticsPage');

    if (counts.size > 1) {
        return (
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button
                        type="button"
                        variant="secondary"
                        size="toolbar"
                        menu
                        aria-label={t('currency')}
                        className="text-xs ring-1 ring-border"
                    >
                        {currency}
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                    {Array.from(counts.entries())
                        .sort((a, b) => b[1] - a[1])
                        .map(([code, count]) => (
                            <DropdownMenuItem key={code} onSelect={() => onChange(code)}>
                                <span className={code === currency ? 'font-semibold' : ''}>{code}</span>
                                <span className="ml-auto text-xs text-muted-foreground">
                                    {t('currencyCount', { count })}
                                </span>
                            </DropdownMenuItem>
                        ))}
                </DropdownMenuContent>
            </DropdownMenu>
        );
    }

    return hasDeals ? (
        <span className="rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-muted-foreground ring-1 ring-border">
            {currency}
        </span>
    ) : null;
}
