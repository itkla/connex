"use client";

import { useMemo } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { ArrowUpRightIcon } from "@heroicons/react/24/outline";

import type { TemperatureBand, TemperatureTrend, User } from "@/app/lib/types";
import { cn } from "@/lib/utils";
import { warmthDotClass } from "@/app/lib/utils";

/** A single relationship rendered as a node in the warmth constellation. */
export type ConstellationNode = {
    id: number;
    name: string;
    company?: string | null;
    imageUrl?: string | null;
    band: TemperatureBand;
    score: number;
    daysSinceTouch?: number | null;
    trend: TemperatureTrend;
};

type Distribution = Record<TemperatureBand, number>;

type Props = {
    user: User;
    greeting: string;
    nodes: ConstellationNode[];
    distribution: Distribution;
    coolingCount: number;
};

const BANDS: TemperatureBand[] = ["hot", "warm", "cool", "cold"];
const RING_RADIUS: Record<TemperatureBand, number> = { hot: 0.52, warm: 0.73, cool: 0.9, cold: 1 };
const RING_PHASE: Record<TemperatureBand, number> = { hot: 0.08, warm: 0.6, cool: 0.3, cold: 0.86 };
const NODE_TEXT: Record<TemperatureBand, string> = {
    hot: "text-white",
    warm: "text-neutral-900",
    cool: "text-neutral-900",
    cold: "text-white",
};
const AURA_TINT: Record<TemperatureBand, string> = {
    hot: "var(--warmth-hot)",
    warm: "var(--warmth-warm)",
    cool: "var(--warmth-cool)",
    cold: "var(--warmth-cold)",
};
const R = 40;

type PlacedNode = ConstellationNode & {
    xPct: number;
    yPct: number;
    size: number;
    driftX: number;
    driftY: number;
    dur: number;
    delay: number;
};

function placeNodes(nodes: ConstellationNode[]): PlacedNode[] {
    const byBand = new Map<TemperatureBand, ConstellationNode[]>();
    for (const b of BANDS) byBand.set(b, []);
    for (const n of nodes) byBand.get(n.band)?.push(n);

    const placed: PlacedNode[] = [];
    for (const band of BANDS) {
        const group = byBand.get(band) ?? [];
        const count = group.length;
        group.forEach((node, i) => {
            const jitterR = ((node.id * 37) % 100) / 100 - 0.5;
            const jitterA = ((node.id * 53) % 100) / 100 - 0.5;
            const angle = (i / Math.max(count, 1) + RING_PHASE[band] + jitterA * 0.05) * Math.PI * 2;
            const r = RING_RADIUS[band] * (1 + jitterR * 0.1);
            placed.push({
                ...node,
                xPct: round3(50 + Math.cos(angle) * r * R),
                yPct: round3(50 + Math.sin(angle) * r * R),
                size: 26 + Math.round((node.score / 100) * 16),
                driftX: (jitterA > 0 ? 1 : -1) * (5 + ((node.id * 7) % 6)),
                driftY: (jitterR > 0 ? -1 : 1) * (6 + ((node.id * 11) % 7)),
                dur: 7 + ((node.id * 13) % 6),
                delay: (node.id % 10) * 0.35,
            });
        });
    }
    return placed;
}

/** Rounds to 3 decimals so server and client render byte-identical coordinates (no hydration drift). */
function round3(n: number): number {
    return Math.round(n * 1000) / 1000;
}

function initialsOf(name: string): string {
    const parts = name.trim().split(/\s+/);
    return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase() || "?";
}

export default function MeHero({ user, greeting, nodes, distribution, coolingCount }: Props) {
    const t = useTranslations("MePage");
    const placed = useMemo(() => placeNodes(nodes), [nodes]);

    const total = BANDS.reduce((sum, b) => sum + distribution[b], 0);
    const dominant = BANDS.reduce((best, b) => (distribution[b] > distribution[best] ? b : best), "warm" as TemperatureBand);
    const warmShare = total > 0 ? (distribution.hot + distribution.warm) / total : 0;
    const initials = initialsOf(user.displayName || user.username || "?");

    const signalRead =
        total === 0
            ? t("signalEmpty")
            : t(`signal_${warmShare >= 0.5 ? "warm" : "cool"}_${coolingCount > 0 ? "cooling" : "steady"}`, {
                  count: coolingCount,
              });

    return (
        <section
            aria-label={t("networkAria")}
            className="relative isolate overflow-hidden rounded-3xl border border-border bg-card"
        >
            <div
                aria-hidden
                className="pointer-events-none absolute inset-0"
                style={{
                    background: `radial-gradient(58% 60% at 50% 52%, color-mix(in oklch, ${AURA_TINT[dominant]} 20%, transparent), transparent 72%)`,
                }}
            />
            <div
                aria-hidden
                className="pointer-events-none absolute inset-0 opacity-50 [background-image:radial-gradient(color-mix(in_oklch,var(--muted-foreground)_32%,transparent)_1px,transparent_1px)] [background-size:22px_22px] [mask-image:radial-gradient(75%_75%_at_50%_52%,black,transparent)]"
            />

            <div className="relative flex flex-col gap-5 p-6 sm:p-8">
                <header className="max-w-md">
                    <p className="text-sm font-medium text-muted-foreground">{greeting}</p>
                    <h1 className="mt-1 text-4xl font-extrabold tracking-tight text-balance text-foreground sm:text-5xl">
                        {user.displayName}
                    </h1>
                </header>

                <div className="relative min-h-[19rem] sm:min-h-[22rem]">
                    <ConstellationField placed={placed} />
                    <div className="pointer-events-none absolute inset-0 grid place-items-center">
                        <div className="relative">
                            <span
                                aria-hidden
                                className="me-halo absolute -inset-5 rounded-full bg-[radial-gradient(circle,color-mix(in_oklch,var(--color-brand)_42%,transparent),transparent_70%)]"
                            />
                            <span className="ncd-sheen relative grid size-20 place-items-center overflow-hidden rounded-full bg-gradient-to-br from-brand-light to-brand-dark text-2xl font-semibold text-white shadow-[0_18px_45px_-18px_rgba(0,0,0,0.55)] ring-2 ring-background sm:size-28 sm:text-3xl">
                                {user.profilePictureUrl ? (
                                    <img src={user.profilePictureUrl} alt="" className="size-full object-cover" />
                                ) : (
                                    initials
                                )}
                            </span>
                        </div>
                    </div>
                </div>

                <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
                    <div className="max-w-md">
                        <p className="text-base text-pretty text-foreground/90">{signalRead}</p>
                        <Link
                            href="/overview/map"
                            className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-brand transition-colors hover:text-brand-hover"
                        >
                            {t("exploreNetwork")}
                            <ArrowUpRightIcon className="size-4" />
                        </Link>
                    </div>
                    {total > 0 && (
                        <ul className="flex flex-wrap gap-x-4 gap-y-1.5 self-start rounded-full border border-border bg-background/70 px-4 py-2 backdrop-blur-sm sm:self-auto">
                            {BANDS.filter((b) => distribution[b] > 0).map((b) => (
                                <li key={b} className="flex items-center gap-1.5 text-xs text-muted-foreground">
                                    <span className={cn("size-2 rounded-full", warmthDotClass(b))} />
                                    <span className="font-medium tabular-nums text-foreground">{distribution[b]}</span>
                                    {t(b)}
                                </li>
                            ))}
                        </ul>
                    )}
                </div>
            </div>

            {total === 0 && (
                <div className="relative border-t border-border px-8 py-4 text-center text-sm text-muted-foreground">
                    {t("networkEmptyHint")}
                </div>
            )}
        </section>
    );

    function ConstellationField({ placed }: { placed: PlacedNode[] }) {
        return (
            <>
                <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="absolute inset-0 size-full" aria-hidden>
                    {BANDS.map((b) => (
                        <ellipse
                            key={b}
                            cx={50}
                            cy={50}
                            rx={RING_RADIUS[b] * R}
                            ry={RING_RADIUS[b] * R}
                            fill="none"
                            stroke="var(--border)"
                            strokeWidth={0.15}
                            opacity={0.55}
                        />
                    ))}
                    {placed.map((n) => (
                        <line
                            key={n.id}
                            x1={50}
                            y1={50}
                            x2={n.xPct}
                            y2={n.yPct}
                            stroke="var(--border)"
                            strokeWidth={0.12}
                            opacity={0.45}
                        />
                    ))}
                </svg>
                {placed.map((n) => (
                    <div
                        key={n.id}
                        className="absolute -translate-x-1/2 -translate-y-1/2"
                        style={{ left: `${n.xPct}%`, top: `${n.yPct}%` }}
                    >
                        <div
                            className="me-drift"
                            style={
                                {
                                    "--drift-x": `${n.driftX}px`,
                                    "--drift-y": `${n.driftY}px`,
                                    "--drift-dur": `${n.dur}s`,
                                    "--drift-delay": `${n.delay}s`,
                                } as React.CSSProperties
                            }
                        >
                            <div className="group pointer-events-auto relative grid place-items-center">
                                <span
                                    aria-hidden
                                    className={cn("absolute rounded-full opacity-40 blur-[7px]", warmthDotClass(n.band))}
                                    style={{ width: n.size + 8, height: n.size + 8 }}
                                />
                                <span
                                    aria-hidden
                                    className={cn(
                                        "grid place-items-center overflow-hidden rounded-full text-[0.6rem] font-semibold ring-2 ring-background/80 transition-transform duration-200 ease-out group-hover:scale-110",
                                        warmthDotClass(n.band),
                                        NODE_TEXT[n.band],
                                        n.trend === "cooling" && "outline outline-2 outline-offset-1 outline-warmth-cool/60",
                                    )}
                                    style={{ width: n.size, height: n.size }}
                                >
                                    {n.imageUrl ? (
                                        <img src={n.imageUrl} alt="" className="size-full object-cover" />
                                    ) : (
                                        initialsOf(n.name)
                                    )}
                                </span>
                                <span className="pointer-events-none absolute bottom-[calc(100%+6px)] left-1/2 z-30 w-max max-w-[12rem] -translate-x-1/2 scale-95 rounded-lg border border-border bg-popover px-2.5 py-1.5 text-left opacity-0 shadow-lg transition-all duration-150 ease-out group-hover:scale-100 group-hover:opacity-100 group-focus-within:scale-100 group-focus-within:opacity-100">
                                    <span className="block truncate text-xs font-medium text-popover-foreground">{n.name}</span>
                                    <span className="block truncate text-[0.7rem] text-muted-foreground">
                                        {n.company ? `${t(n.band)} · ${n.company}` : t(n.band)}
                                    </span>
                                    <span className="block text-[0.7rem] text-muted-foreground">
                                        {n.daysSinceTouch != null
                                            ? t("touchDaysAgo", { count: n.daysSinceTouch })
                                            : t("neverTouched")}
                                    </span>
                                </span>
                            </div>
                        </div>
                    </div>
                ))}
            </>
        );
    }
}
