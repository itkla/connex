"use client";

import { useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";

import type { Deal, DealMetrics } from "@/app/lib/types";
import { useOwnedUrlParams } from "@/app/hooks/useOwnedUrlParams";
import CurrencyControl from "@/app/components/overview/analytics/CurrencyControl";
import { currencyCountsForMetrics, resolveCurrency } from "@/app/components/overview/analytics/currencyScope";
import { toTiles } from "@/app/components/overview/analytics/KpiCluster";
import { resolveAnalyticsWindow } from "@/app/components/overview/analytics/metrics";
import { personalDealKpis } from "./personalDealKpis";
import SignalStrip from "./SignalStrip";

/** Personal deal KPIs using Analytics' currency selection and reporting window. */
export default function PersonalDealSignals({ deals, dealMetrics, timezone }: { deals: Deal[]; dealMetrics: DealMetrics; timezone: string }) {
    const t = useTranslations("MePage");
    const searchParams = useSearchParams();
    const [selectedCurrency, setSelectedCurrency] = useState(() => searchParams.get("currency"));
    const counts = useMemo(() => currencyCountsForMetrics(dealMetrics), [dealMetrics]);
    const currency = resolveCurrency(counts, selectedCurrency);
    const [now] = useState(() => Date.now());
    const window = useMemo(() => resolveAnalyticsWindow("90d", now, timezone), [now, timezone]);
    const kpis = useMemo(() => toTiles(personalDealKpis(deals, currency, window, timezone)), [deals, currency, window, timezone]);

    useOwnedUrlParams({ currency: selectedCurrency && counts.has(selectedCurrency) ? selectedCurrency : undefined });

    return (
        <section aria-label={t("dealSignals")} className="space-y-3">
            <div className="flex items-center justify-between gap-3">
                <h2 className="text-sm font-medium text-muted-foreground">{t("dealSignals")}</h2>
                <CurrencyControl counts={counts} currency={currency} hasDeals={dealMetrics.totalCount > 0} onChange={setSelectedCurrency} />
            </div>
            <SignalStrip kpis={kpis} currency={currency} />
        </section>
    );
}
