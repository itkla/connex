"use client";

import { useMemo } from "react";
import { useTranslations } from "next-intl";

import { cn } from "@/lib/utils";

/** One day of personal activity for the pulse heatmap. */
export type PulseDay = { date: string; count: number };

const LEVEL_CLASS = [
    "bg-muted",
    "bg-brand-light",
    "bg-brand/40",
    "bg-brand/70",
    "bg-brand",
] as const;

function level(count: number): number {
    if (count <= 0) return 0;
    if (count === 1) return 1;
    if (count === 2) return 2;
    if (count <= 4) return 3;
    return 4;
}

export default function PulseStrip({
    days,
    totalTouches,
    streak,
}: {
    days: PulseDay[];
    totalTouches: number;
    streak: number;
}) {
    const t = useTranslations("MePage");

    const weeks = useMemo(() => {
        const cols: PulseDay[][] = [];
        for (let i = 0; i < days.length; i += 7) cols.push(days.slice(i, i + 7));
        return cols;
    }, [days]);

    return (
        <div className="overflow-hidden rounded-2xl border border-border bg-card p-5">
            <div className="flex flex-wrap items-end justify-between gap-4">
                <div>
                    <h2 className="text-sm font-semibold text-foreground">{t("pulseTitle")}</h2>
                    <p className="mt-0.5 text-xs text-muted-foreground">{t("pulseSubtitle")}</p>
                </div>
                <div className="flex items-center gap-6">
                    <div className="text-right">
                        <div className="text-2xl font-semibold leading-none tabular-nums text-foreground">
                            {totalTouches}
                        </div>
                        <div className="mt-1 text-xs text-muted-foreground">{t("pulseTouches")}</div>
                    </div>
                    <div className="text-right">
                        <div className="text-2xl font-semibold leading-none tabular-nums text-foreground">
                            {streak}
                        </div>
                        <div className="mt-1 text-xs text-muted-foreground">{t("pulseStreak")}</div>
                    </div>
                </div>
            </div>

            <div className="mt-5 overflow-x-auto">
                <div className="flex gap-1" role="img" aria-label={t("pulseAria", { count: totalTouches })}>
                    {weeks.map((week, wi) => (
                        <div key={wi} className="flex flex-col gap-1">
                            {week.map((day) => (
                                <span
                                    key={day.date}
                                    title={t("pulseDay", { count: day.count, date: day.date })}
                                    className={cn("size-3 rounded-[3px] ring-1 ring-border/50", LEVEL_CLASS[level(day.count)])}
                                />
                            ))}
                        </div>
                    ))}
                </div>
            </div>

            <div className="mt-4 flex items-center justify-end gap-1.5 text-xs text-muted-foreground">
                <span>{t("pulseLess")}</span>
                {LEVEL_CLASS.map((c, i) => (
                    <span key={i} className={cn("size-3 rounded-[3px] ring-1 ring-border/50", c)} />
                ))}
                <span>{t("pulseMore")}</span>
            </div>
        </div>
    );
}
