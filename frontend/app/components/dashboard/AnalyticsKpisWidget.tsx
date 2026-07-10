'use client';

import { type DealKpis } from '@/app/lib/types';
import KpiCluster from '@/app/components/overview/analytics/KpiCluster';

/**
 * Props for {@link AnalyticsKpisWidget}. `kpis` is the server-computed {@link DealKpis} DTO for
 * `currency`, already scoped to the caller's comparison window.
 */
export type AnalyticsKpisWidgetProps = {
    kpis: DealKpis;
    currency: string;
};

/**
 * Dashboard widget wrapping the shared {@link KpiCluster}. A thin pass-through: KPI aggregation
 * happens server-side, so this simply forwards the DTO.
 */
export default function AnalyticsKpisWidget({ kpis, currency }: AnalyticsKpisWidgetProps) {
    return <KpiCluster kpis={kpis} currency={currency} />;
}
