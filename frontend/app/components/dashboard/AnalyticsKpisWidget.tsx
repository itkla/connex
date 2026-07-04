'use client';

import { useMemo, useState } from 'react';

import { type Deal } from '@/app/lib/types';
import KpiCluster from '@/app/components/overview/analytics/KpiCluster';
import { computeKpis, RANGE_DAYS, type RangeKey } from '@/app/components/overview/analytics/metrics';

/**
 * Props for {@link AnalyticsKpisWidget}. `deals` must already be filtered to `currency`
 * by the caller; `range` selects the comparison window (defaults to `'90d'`).
 */
export type AnalyticsKpisWidgetProps = {
    deals: Deal[];
    currency: string;
    range?: RangeKey;
};

/**
 * Dashboard widget that derives the analytics KPI cluster (won revenue, new pipeline,
 * win rate, average cycle) from a currency-filtered set of deals and renders the shared
 * {@link KpiCluster}. `now` is seeded once on mount to keep the computed window stable
 * across renders and avoid a hydration mismatch.
 */
export default function AnalyticsKpisWidget({ deals, currency, range = '90d' }: AnalyticsKpisWidgetProps) {
    const [now] = useState(() => Date.now());
    const kpis = useMemo(() => computeKpis(deals, now, RANGE_DAYS[range]), [deals, now, range]);

    return <KpiCluster kpis={kpis} currency={currency} />;
}
