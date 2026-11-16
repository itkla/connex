"use client";

import { useTransition } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";

import type { MyWorkReadSections } from "@/app/lib/types";
import CurrencyControl from "@/app/components/overview/analytics/CurrencyControl";
import { currencyCountsForMetrics, toTiles } from "@/app/components/overview/analytics/currencyScope";
import SignalStrip from "./SignalStrip";

/** Complete personal KPI aggregates for one currency; navigation re-fetches the selected partition. */
export default function PersonalDealSignals({ data }: { data: MyWorkReadSections["deal-kpis"] }) {
    const t = useTranslations("MePage");
    const router = useRouter();
    const pathname = usePathname();
    const searchParams = useSearchParams();
    const [pending, startTransition] = useTransition();
    const counts = currencyCountsForMetrics(data.metrics);

    const selectCurrency = (currency: string) => {
        if (!counts.has(currency) || (currency === data.currency && !pending)) return;
        const params = new URLSearchParams(searchParams.toString());
        params.set("currency", currency);
        startTransition(() => router.replace(`${pathname}?${params}`, { scroll: false }));
    };

    return (
        <section aria-label={t("dealSignals")} aria-busy={pending} className="space-y-3">
            <div className="flex items-center justify-between gap-3">
                <h2 className="text-sm font-medium text-muted-foreground">{pending ? t("loadingSignals") : t("dealSignals")}</h2>
                <CurrencyControl counts={counts} currency={data.currency} hasDeals={data.metrics.totalCount > 0} onChange={selectCurrency} />
            </div>
            <SignalStrip kpis={toTiles(data.kpis)} currency={data.currency} />
        </section>
    );
}
