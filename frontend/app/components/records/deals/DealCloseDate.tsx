'use client';

import { useLocale, useTranslations } from 'next-intl';
import type { Deal } from '@/app/lib/types';
import { formatDealClosedAt } from './dealDates';
import { isDealClosed } from './dealOutcome';

/** Shows a realized close day on the same reporting calendar as its revenue bucket. */
export default function DealCloseDate({ deal, timezone }: { deal: Deal; timezone: string }) {
    const locale = useLocale();
    const t = useTranslations('DealsCard');
    if (!isDealClosed(deal) || !deal.closedAt) return null;
    return <span className="mt-0.5 block text-xs text-muted-foreground">{t('closedOn', { date: formatDealClosedAt(deal.closedAt, locale, timezone) })}</span>;
}
