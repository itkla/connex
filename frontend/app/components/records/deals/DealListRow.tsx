'use client';

import { useLocale, useTranslations } from 'next-intl';

import { formatCompactCurrency } from '@/app/lib/utils';
import type { Company, Deal, DealRisk, Stage } from '@/app/lib/types';
import DealRiskPill from './DealRiskPill';
import { isDealClosed } from './dealOutcome';

/**
 * A deal as one row of the viewport-forced mobile list: the name over its position in the pipeline
 * (stage and account), with the amount trailing because value is what a phone user ranks a deal
 * list by.
 *
 * Two of the desktop card's cues are conditional so the row can never outgrow one line. The closed
 * badge only appears once a deal is closed — open is the expected state and needs no badge — and the
 * risk pill is held back until `sm`, where there is room for it beside the name, mirroring how the
 * task list defers its secondary chips.
 */
export default function DealListRow({
    deal,
    company,
    stage,
    risk,
}: {
    deal: Deal;
    company?: Company;
    stage?: Stage;
    risk?: DealRisk | null;
}) {
    const t = useTranslations('DealsCard');
    const locale = useLocale();
    const secondary = [stage?.name, company?.name].filter((part) => !!part?.trim()).join(' · ');

    return (
        <span className="flex min-w-0 items-center gap-2">
            <span className="min-w-0 flex-1">
                <span className="flex min-w-0 items-center gap-1.5">
                    <span className="truncate text-sm font-medium text-foreground">{deal.name}</span>
                    {isDealClosed(deal) && (
                        <span className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium tracking-wider text-muted-foreground uppercase">
                            {t('statusClosed')}
                        </span>
                    )}
                    <span className="hidden shrink-0 sm:inline-flex">
                        <DealRiskPill risk={risk} />
                    </span>
                </span>
                {secondary && <span className="mt-0.5 block truncate text-xs text-muted-foreground">{secondary}</span>}
            </span>
            <span className="shrink-0 text-sm font-semibold tabular-nums text-foreground">
                {formatCompactCurrency(deal.value, deal.currency || 'USD', locale)}
            </span>
        </span>
    );
}
