"use client";

import { useTranslations } from "next-intl";
import { ArrowDownIcon, ArrowUpIcon } from "@heroicons/react/16/solid";
import {
    ArrowTrendingUpIcon,
    BanknotesIcon,
    ClockIcon,
    TrophyIcon,
} from "@heroicons/react/24/outline";

import type { Kpi } from "@/app/components/overview/analytics/metrics";
import CountUp from "@/app/components/dashboard/CountUp";
import { cn } from "@/lib/utils";

const ICONS = {
    wonRevenue: BanknotesIcon,
    winRate: TrophyIcon,
    newPipeline: ArrowTrendingUpIcon,
    avgCycle: ClockIcon,
} as const;

function Sparkline({ series, positive }: { series: number[]; positive: boolean }) {
    const w = 72;
    const h = 26;
    const pad = 3;
    const stroke = positive ? "var(--color-brand)" : "var(--muted-foreground)";
    if (series.length < 2 || series.every((v) => v === 0)) {
        return (
            <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden className="overflow-visible">
                <line x1={pad} y1={h - pad} x2={w - pad} y2={h - pad} stroke="var(--border)" strokeWidth={1.5} />
            </svg>
        );
    }
    const max = Math.max(...series);
    const min = Math.min(...series);
    const range = max - min || 1;
    const step = (w - 2 * pad) / (series.length - 1);
    const pts = series.map((v, i) => [pad + i * step, h - pad - ((v - min) / range) * (h - 2 * pad)] as const);
    const line = pts.map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(1)} ${y.toFixed(1)}`).join(" ");
    const area = `${line} L${(w - pad).toFixed(1)} ${h - pad} L${pad.toFixed(1)} ${h - pad} Z`;
    return (
        <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden className="overflow-visible">
            <defs>
                <linearGradient id={`me-spark-${positive ? "up" : "flat"}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={stroke} stopOpacity={0.2} />
                    <stop offset="100%" stopColor={stroke} stopOpacity={0} />
                </linearGradient>
            </defs>
            <path d={area} fill={`url(#me-spark-${positive ? "up" : "flat"})`} />
            <path d={line} fill="none" stroke={stroke} strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" />
            <circle cx={pts[pts.length - 1][0]} cy={pts[pts.length - 1][1]} r={2.2} fill={stroke} />
        </svg>
    );
}

function Delta({ kpi, label }: { kpi: Kpi; label: string }) {
    if (kpi.delta == null) return <span className="text-xs text-muted-foreground">{label}</span>;
    const flat = Math.abs(kpi.delta) < 0.005;
    const up = kpi.delta > 0;
    const good = flat ? null : up === kpi.goodWhenUp;
    const tone =
        good == null
            ? "text-muted-foreground"
            : good
              ? "text-emerald-700 dark:text-emerald-400"
              : "text-red-600 dark:text-red-400";
    const magnitude = Math.round(Math.abs(kpi.delta) * 100);
    const Icon = up ? ArrowUpIcon : ArrowDownIcon;
    return (
        <span className={cn("inline-flex items-center gap-1 text-xs font-medium tabular-nums", tone)}>
            {!flat && <Icon className="size-3" />}
            {magnitude}
            {kpi.deltaKind === "pp" ? "pp" : "%"}
        </span>
    );
}

export default function SignalStrip({ kpis, currency }: { kpis: Kpi[]; currency: string }) {
    const t = useTranslations("MePage");

    const renderValue = (kpi: Kpi) => {
        if (kpi.format === "currency") return <CountUp value={kpi.value} format="currency" currency={currency} />;
        if (kpi.format === "percent")
            return (
                <>
                    <CountUp value={Math.round(kpi.value * 100)} format="count" />%
                </>
            );
        return (
            <>
                <CountUp value={Math.round(kpi.value)} format="count" /> {t("daysUnit")}
            </>
        );
    };

    return (
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-2 lg:grid-cols-4">
            {kpis.map((kpi) => {
                const Icon = ICONS[kpi.key];
                return (
                    <div key={kpi.key} className="flex flex-col gap-3 bg-card p-5">
                        <div className="flex items-start justify-between gap-3">
                            <span className="grid size-8 place-items-center rounded-lg bg-brand-light text-brand-dark">
                                <Icon className="size-4" />
                            </span>
                            <Sparkline series={kpi.series} positive={kpi.goodWhenUp} />
                        </div>
                        <div>
                            <div className="text-2xl font-semibold leading-none tabular-nums text-foreground">
                                {renderValue(kpi)}
                            </div>
                            <div className="mt-2 flex items-center justify-between gap-2">
                                <span className="text-xs font-medium uppercase tracking-[0.1em] text-muted-foreground">
                                    {t(`kpi_${kpi.key}`)}
                                </span>
                                <Delta kpi={kpi} label={t("kpiNoBaseline")} />
                            </div>
                        </div>
                    </div>
                );
            })}
        </div>
    );
}
