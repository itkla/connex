"use client";

import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { CheckCircleIcon, FireIcon, ExclamationTriangleIcon } from "@heroicons/react/24/outline";

import type { DealRiskFactorCode, DealRiskLevel, RelationshipTemperature } from "@/app/lib/types";
import { cn } from "@/lib/utils";
import { formatCompactCurrency } from "@/app/lib/utils";
import TemperaturePill from "@/app/components/records/TemperaturePill";

/** A relationship that is cooling and needs a touch, joined to its contact. */
export type CoolingItem = {
    id: number;
    name: string;
    company?: string | null;
    temp: RelationshipTemperature;
};

/** A personal deal flagged at risk, joined to display fields. */
export type RiskItem = {
    id: number;
    name: string;
    value: number;
    currency: string;
    level: DealRiskLevel;
    topFactor?: DealRiskFactorCode | null;
};

const RISK_TONE: Record<Exclude<DealRiskLevel, "none">, string> = {
    high: "bg-risk-high/12 text-risk-high ring-risk-high/25",
    medium: "bg-risk-medium/12 text-risk-medium ring-risk-medium/30",
    low: "bg-risk-low/12 text-risk-low ring-risk-low/30",
};

function initials(name: string): string {
    const p = name.trim().split(/\s+/);
    return ((p[0]?.[0] ?? "") + (p[1]?.[0] ?? "")).toUpperCase() || "?";
}

function PanelShell({
    title,
    icon: Icon,
    count,
    children,
}: {
    title: string;
    icon: React.ComponentType<{ className?: string }>;
    count: number;
    children: React.ReactNode;
}) {
    return (
        <div className="flex flex-col overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex items-center gap-2.5 border-b border-border px-5 py-4">
                <span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground">
                    <Icon className="size-4" />
                </span>
                <h2 className="text-sm font-semibold text-foreground">{title}</h2>
                {count > 0 && (
                    <span className="ml-auto rounded-full bg-muted px-2 py-0.5 text-xs font-medium tabular-nums text-muted-foreground">
                        {count}
                    </span>
                )}
            </div>
            {children}
        </div>
    );
}

function EmptyRow({ text }: { text: string }) {
    return (
        <div className="flex flex-1 flex-col items-center justify-center gap-2 px-5 py-10 text-center">
            <CheckCircleIcon className="size-6 text-warmth-hot" />
            <p className="max-w-xs text-sm text-muted-foreground">{text}</p>
        </div>
    );
}

export default function NeedsYou({ cooling, risks }: { cooling: CoolingItem[]; risks: RiskItem[] }) {
    const t = useTranslations("MePage");
    const locale = useLocale();

    return (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <PanelShell title={t("coolingTitle")} icon={FireIcon} count={cooling.length}>
                {cooling.length === 0 ? (
                    <EmptyRow text={t("coolingEmpty")} />
                ) : (
                    <ul className="divide-y divide-border">
                        {cooling.map((c) => (
                            <li key={c.id}>
                                <Link
                                    href={`/records/contacts/${c.id}`}
                                    className="flex items-center gap-3 px-5 py-3 transition-colors hover:bg-muted/50"
                                >
                                    <span className="grid size-9 shrink-0 place-items-center rounded-full bg-muted text-xs font-semibold text-muted-foreground ring-1 ring-border">
                                        {initials(c.name)}
                                    </span>
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">{c.name}</p>
                                        {c.company && (
                                            <p className="truncate text-xs text-muted-foreground">{c.company}</p>
                                        )}
                                    </div>
                                    {c.temp.daysUntilCold != null ? (
                                        <span
                                            className={cn(
                                                "shrink-0 rounded-full px-2 py-0.5 text-xs font-medium tabular-nums ring-1",
                                                c.temp.daysUntilCold <= 14
                                                    ? "bg-warmth-cool/12 text-warmth-cool ring-warmth-cool/30"
                                                    : "bg-muted text-muted-foreground ring-border",
                                            )}
                                        >
                                            {t("coldIn", { count: c.temp.daysUntilCold })}
                                        </span>
                                    ) : (
                                        <TemperaturePill temp={c.temp} />
                                    )}
                                </Link>
                            </li>
                        ))}
                    </ul>
                )}
            </PanelShell>

            <PanelShell title={t("riskTitle")} icon={ExclamationTriangleIcon} count={risks.length}>
                {risks.length === 0 ? (
                    <EmptyRow text={t("riskEmpty")} />
                ) : (
                    <ul className="divide-y divide-border">
                        {risks.map((d) => (
                            <li key={d.id}>
                                <Link
                                    href={`/records/deals/${d.id}`}
                                    className="flex items-center gap-3 px-5 py-3 transition-colors hover:bg-muted/50"
                                >
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">{d.name}</p>
                                        <p className="truncate text-xs text-muted-foreground">
                                            {d.topFactor ? t(`riskFactor_${d.topFactor}`) : t("riskGeneric")}
                                        </p>
                                    </div>
                                    <span className="shrink-0 text-sm font-medium tabular-nums text-foreground">
                                        {formatCompactCurrency(d.value, d.currency, locale)}
                                    </span>
                                    {d.level !== "none" && (
                                        <span
                                            className={cn(
                                                "shrink-0 rounded-full px-2 py-0.5 text-xs font-medium capitalize ring-1",
                                                RISK_TONE[d.level],
                                            )}
                                        >
                                            {t(`riskLevel_${d.level}`)}
                                        </span>
                                    )}
                                </Link>
                            </li>
                        ))}
                    </ul>
                )}
            </PanelShell>
        </div>
    );
}
