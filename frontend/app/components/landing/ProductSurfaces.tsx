import { getTranslations } from "next-intl/server";
import { RadarMark } from "@/app/components/radar/RadarVocabulary";
import { cn } from "@/lib/utils";

/**
 * Abstract diagrams of how Connex reasons, for the landing page.
 *
 * These are deliberately not screenshots. A marketing page that mimics real UI either lies about
 * a customer that does not exist or ages badly the moment the product moves; a diagram states the
 * shape of the idea and lets the surrounding copy carry the words. Text inside a surface is kept
 * to axis labels and counts.
 *
 * Where the product already owns a visual vocabulary the diagrams borrow it directly — Radar's
 * {@link RadarMark} and the shared `--warmth-*` tokens — so the abstraction stays truthful to what
 * the app actually draws.
 */

/** Chrome shared by every surface, so each one reads as a window into the product. */
function SurfaceFrame({
    label,
    sampleLabel,
    children,
}: {
    label: string;
    sampleLabel: string;
    children: React.ReactNode;
}) {
    return (
        <div className="overflow-hidden rounded-2xl border border-border bg-card shadow-[0_28px_70px_-46px] shadow-foreground/25">
            <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-3">
                <span className="text-sm font-medium text-foreground">{label}</span>
                <span className="rounded-full bg-muted px-2.5 py-1 text-[11px] font-medium text-muted-foreground">
                    {sampleLabel}
                </span>
            </div>
            {children}
        </div>
    );
}

/** Which Radar family a tone belongs to, mirroring `RADAR_TONE_FAMILY`. */
const TONE_FAMILY = {
    hot: "relationship_decay",
    warm: "relationship_decay",
    cool: "relationship_decay",
    cold: "relationship_decay",
    high: "deal_risk",
    medium: "deal_risk",
    low: "deal_risk",
    path: "warm_path",
} as const;

/** Blips on the sweep, in polar coordinates: angle in degrees, radius as a fraction of the disc. */
const BLIPS = [
    { deg: 18, r: 0.34, tone: "cold", size: 11, delay: 0 },
    { deg: 74, r: 0.66, tone: "high", size: 13, delay: -0.6 },
    { deg: 122, r: 0.44, tone: "cool", size: 9, delay: -1.9 },
    { deg: 168, r: 0.78, tone: "medium", size: 10, delay: -1.1 },
    { deg: 208, r: 0.29, tone: "warm", size: 8, delay: -2.6 },
    { deg: 252, r: 0.6, tone: "cool", size: 10, delay: -0.3 },
    { deg: 297, r: 0.83, tone: "path", size: 9, delay: -2.2 },
    { deg: 331, r: 0.5, tone: "hot", size: 12, delay: -1.5 },
] as const;

const BLIP_BG = {
    hot: "bg-warmth-hot",
    warm: "bg-warmth-warm",
    cool: "bg-warmth-cool",
    cold: "bg-warmth-cold",
    high: "bg-risk-high",
    medium: "bg-risk-medium",
    low: "bg-risk-low",
    path: "bg-chart-5",
} as const;

/**
 * The sweep: Connex's namesake surface, drawn as the instrument it is named after.
 *
 * Concentric rings, a conic gradient rotating beneath them, and blips that pulse on their own
 * offsets so the field never breathes in unison. Everything is CSS, so the animation runs off the
 * main thread and stops dead under `prefers-reduced-motion`.
 */
export async function RadarSweepSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <div className="relative mx-auto aspect-square w-full max-w-lg" aria-hidden>
            <div className="absolute inset-0 rounded-full bg-brand/5 blur-3xl" />

            <div className="absolute inset-0 overflow-hidden rounded-full">
                <div className="connex-radar-sweep absolute inset-0 origin-center" />
            </div>

            {[1, 0.74, 0.48, 0.24].map((scale) => (
                <div
                    key={scale}
                    className="absolute rounded-full border border-brand/25 dark:border-brand/20"
                    style={{
                        inset: `${((1 - scale) / 2) * 100}%`,
                    }}
                />
            ))}

            <div className="absolute top-1/2 left-0 h-px w-full -translate-y-1/2 bg-brand/15" />
            <div className="absolute top-0 left-1/2 h-full w-px -translate-x-1/2 bg-brand/15" />

            {BLIPS.map((blip) => {
                const rad = (blip.deg * Math.PI) / 180;
                const left = 50 + Math.cos(rad) * blip.r * 50;
                const top = 50 + Math.sin(rad) * blip.r * 50;
                return (
                    <div key={blip.deg} className="absolute" style={{ left: `${left}%`, top: `${top}%` }}>
                        <span
                            className={cn(
                                "connex-blip-ring absolute rounded-full",
                                BLIP_BG[blip.tone],
                                "opacity-40",
                            )}
                            style={{
                                width: blip.size * 2.4,
                                height: blip.size * 2.4,
                                animationDelay: `${blip.delay}s`,
                            }}
                        />
                        <span
                            className={cn("connex-blip absolute rounded-full", BLIP_BG[blip.tone])}
                            style={{
                                width: blip.size,
                                height: blip.size,
                                animationDelay: `${blip.delay}s`,
                            }}
                        />
                    </div>
                );
            })}

            <div className="absolute top-1/2 left-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full bg-brand shadow-[0_0_24px] shadow-brand/60" />
            <span className="sr-only">{t("surfaceSweepAlt")}</span>
        </div>
    );
}

/** A constellation of ties. Positions are percentages so the field scales with its container. */
const CONSTELLATION = [
    { id: "a", x: 18, y: 30, size: 13, tone: "warm", dx: "6px", dy: "-5px", dur: 11 },
    { id: "b", x: 42, y: 16, size: 9, tone: "cool", dx: "-5px", dy: "6px", dur: 13 },
    { id: "c", x: 63, y: 28, size: 17, tone: "hot", dx: "4px", dy: "5px", dur: 9 },
    { id: "d", x: 84, y: 20, size: 8, tone: "cold", dx: "-6px", dy: "-4px", dur: 15 },
    { id: "e", x: 30, y: 58, size: 11, tone: "cool", dx: "5px", dy: "4px", dur: 12 },
    { id: "f", x: 55, y: 52, size: 22, tone: "warm", dx: "-4px", dy: "-6px", dur: 10 },
    { id: "g", x: 78, y: 62, size: 12, tone: "cool", dx: "6px", dy: "-4px", dur: 14 },
    { id: "h", x: 14, y: 80, size: 9, tone: "cold", dx: "-5px", dy: "5px", dur: 12 },
    { id: "i", x: 44, y: 86, size: 14, tone: "hot", dx: "4px", dy: "-6px", dur: 16 },
    { id: "j", x: 70, y: 88, size: 10, tone: "cool", dx: "-6px", dy: "4px", dur: 11 },
] as const;

const TIES = [
    ["a", "b"], ["b", "c"], ["c", "d"], ["a", "e"], ["e", "f"], ["f", "c"],
    ["f", "g"], ["g", "d"], ["e", "h"], ["h", "i"], ["i", "f"], ["i", "j"], ["j", "g"],
] as const;

const NODE_BG = {
    hot: "bg-warmth-hot",
    warm: "bg-warmth-warm",
    cool: "bg-warmth-cool",
    cold: "bg-warmth-cold",
} as const;

/**
 * The network, alive.
 *
 * Ties are drawn once in SVG and the points drift over them on independent clocks, so the field
 * never pulses in step. The drift is small enough that the ties stay legible without re-laying the
 * lines every frame, which keeps the whole thing on the compositor.
 */
export async function LivingNetworkSurface() {
    const t = await getTranslations("CommonHome");
    const at = (id: string) => CONSTELLATION.find((n) => n.id === id)!;

    return (
        <div className="relative aspect-[4/3] w-full" aria-hidden>
            <div className="absolute inset-0 rounded-[2rem] bg-brand/5 blur-3xl" />
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="absolute inset-0 size-full">
                {TIES.map(([from, to]) => (
                    <line
                        key={`${from}-${to}`}
                        x1={at(from).x}
                        y1={at(from).y}
                        x2={at(to).x}
                        y2={at(to).y}
                        vectorEffect="non-scaling-stroke"
                        strokeWidth="1"
                        className="stroke-brand/25"
                    />
                ))}
            </svg>
            {CONSTELLATION.map((node) => (
                <span
                    key={node.id}
                    className="connex-node-drift absolute -translate-x-1/2 -translate-y-1/2"
                    style={{
                        left: `${node.x}%`,
                        top: `${node.y}%`,
                        ["--drift-x" as string]: node.dx,
                        ["--drift-y" as string]: node.dy,
                        animationDuration: `${node.dur}s`,
                    }}
                >
                    <span
                        className={cn("block rounded-full", NODE_BG[node.tone])}
                        style={{
                            width: node.size,
                            height: node.size,
                            boxShadow: `0 0 ${node.size * 1.6}px currentColor`,
                        }}
                    />
                </span>
            ))}
            <span className="sr-only">{t("surfaceNetworkAlt")}</span>
        </div>
    );
}

/** Where each logged interaction sits on the decay curve, newest last. */
const DECAY_MARKS = [0.06, 0.19, 0.35, 0.52, 0.78] as const;

/**
 * Warmth as a decay curve: what the team logged, and how much it still counts.
 *
 * `RelationshipWarmthModel` weights an interaction by type and age, so warmth falls away as
 * contact stops. The curve states that shape directly; the marks are the logged interactions
 * feeding it, thinning out toward the present.
 */
export async function WarmthDecaySurface() {
    const t = await getTranslations("CommonHome");
    const curveY = (x: number) => 34 + 118 * x * x;

    return (
        <SurfaceFrame label={t("surfaceWarmthLabel")} sampleLabel={t("surfaceSample")}>
            <div className="px-5 pt-6 pb-4">
                <svg viewBox="0 0 360 190" className="w-full" role="img" aria-label={t("surfaceWarmthAlt")}>
                    <defs>
                        <linearGradient id="warmth-decay" x1="0" y1="0" x2="1" y2="0">
                            <stop offset="0" className="[stop-color:var(--warmth-hot)]" />
                            <stop offset="0.45" className="[stop-color:var(--warmth-warm)]" />
                            <stop offset="0.75" className="[stop-color:var(--warmth-cool)]" />
                            <stop offset="1" className="[stop-color:var(--warmth-cold)]" />
                        </linearGradient>
                    </defs>
                    <line x1="10" y1="162" x2="350" y2="162" className="stroke-border" strokeWidth="1" />
                    <path
                        d={`M 10 ${curveY(0)} ${Array.from({ length: 40 }, (_, i) => {
                            const p = (i + 1) / 40;
                            return `L ${10 + p * 340} ${curveY(p)}`;
                        }).join(" ")}`}
                        fill="none"
                        stroke="url(#warmth-decay)"
                        strokeWidth="3"
                        strokeLinecap="round"
                    />
                    {DECAY_MARKS.map((p) => (
                        <line
                            key={p}
                            x1={10 + p * 340}
                            y1={curveY(p)}
                            x2={10 + p * 340}
                            y2={162}
                            strokeWidth="1"
                            className="stroke-border"
                        />
                    ))}
                    {DECAY_MARKS.map((p) => (
                        <circle
                            key={`dot-${p}`}
                            cx={10 + p * 340}
                            cy={curveY(p)}
                            r="5"
                            strokeWidth="2.5"
                            className="fill-card stroke-brand"
                        />
                    ))}
                </svg>
                <div className="mt-2 flex justify-between text-[11px] text-muted-foreground">
                    <span>{t("surfaceWarmthAxisStart")}</span>
                    <span>{t("surfaceWarmthAxisEnd")}</span>
                </div>
            </div>
        </SurfaceFrame>
    );
}

/**
 * Radar's ranked list, distilled.
 *
 * `RadarSignalCard` renders each row as a mark, a subject, a one-line reading beneath it, and a
 * single action on the right, divided by hairlines. This keeps that anatomy and drops the words:
 * the shape of the row is what carries the meaning — every signal is typed, explained and
 * actionable — so the page shows it instead of asserting it.
 *
 * Bar widths are uneven on purpose. A tidy stack of equal blocks reads as a loading skeleton;
 * ragged ones read as content.
 */
const QUEUE_ROWS = [
    { tone: "cold", subject: 62, reading: 84 },
    { tone: "high", subject: 47, reading: 71 },
    { tone: "cool", subject: 68, reading: 55 },
    { tone: "medium", subject: 40, reading: 78 },
    { tone: "path", subject: 57, reading: 63 },
] as const;

/**
 * The queue as the product draws it, minus the copy.
 *
 * Sizes track `RadarSignalCard`: the mark sits against the first line, the subject bar carries the
 * weight of a semibold heading, and the reading bar below it is lighter and longer.
 */
export async function RadarQueueSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceQueueLabel")} sampleLabel={t("surfaceSample")}>
            <ul aria-hidden className="divide-y divide-border/60">
                {QUEUE_ROWS.map((row, i) => (
                    <li
                        key={row.tone + i}
                        className={cn(
                            "flex items-start gap-3 px-4 py-4 sm:px-5",
                            i === 0 && "bg-muted/40",
                        )}
                    >
                        <RadarMark tone={row.tone} family={TONE_FAMILY[row.tone]} className="mt-1" />
                        <div className="min-w-0 flex-1 space-y-2">
                            <div
                                className="h-2.5 rounded-full bg-foreground/22"
                                style={{ width: `${row.subject}%` }}
                            />
                            <div
                                className="h-2 rounded-full bg-foreground/10"
                                style={{ width: `${row.reading}%` }}
                            />
                        </div>
                        <div className="mt-0.5 h-6 w-16 shrink-0 rounded-full bg-brand-light sm:w-20" />
                    </li>
                ))}
            </ul>
        </SurfaceFrame>
    );
}
