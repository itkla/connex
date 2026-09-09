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

/** SVG fill per warmth band. The `--warmth-*` tokens are the same ones the app's dots use. */
const BAND_FILL = {
    hot: "[fill:var(--warmth-hot)]",
    warm: "[fill:var(--warmth-warm)]",
    cool: "[fill:var(--warmth-cool)]",
    cold: "[fill:var(--warmth-cold)]",
} as const;

/**
 * Radar's five deadline columns. Mark shape names the family and fill names the reading, matching
 * `radarFamilyAccent`; the pile height is the whole point, so the counts are shaped to read as a
 * silhouette rather than to describe any particular workspace.
 */
const HORIZON = [
    { key: "overdue", n: 9, mix: ["cold", "high", "cold", "medium", "cool"] },
    { key: "week", n: 17, mix: ["cool", "high", "medium", "cold", "cool", "warm"] },
    { key: "month", n: 12, mix: ["cool", "medium", "warm", "cool", "low"] },
    { key: "later", n: 6, mix: ["warm", "low", "hot", "warm"] },
    { key: "undated", n: 7, mix: ["path"] },
] as const;

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

/**
 * Radar's horizon: every flagged signal placed on the axis of when it starts costing you.
 *
 * `radarHorizon.ts` calls that deadline "the one fact no other surface in the product can assemble
 * across families", which is the claim this diagram exists to make. The columns carry no row text
 * at all — the pile and the count are the message.
 */
export async function RadarHorizonSurface() {
    const t = await getTranslations("CommonHome");
    const band = await getTranslations("Radar.horizon.band");

    return (
        <SurfaceFrame label={t("surfaceRadarLabel")} sampleLabel={t("surfaceSample")}>
            <div className="grid grid-cols-5 gap-2 px-4 pt-8 pb-4 sm:gap-4 sm:px-6">
                {HORIZON.map((column) => (
                    <div key={column.key} className="flex flex-col justify-end gap-3">
                        <div className="flex h-24 w-full flex-wrap-reverse content-start gap-1.5">
                            {Array.from({ length: column.n }, (_, i) => {
                                const tone = column.mix[i % column.mix.length];
                                return (
                                    <RadarMark
                                        key={`${column.key}-${i}`}
                                        tone={tone}
                                        family={TONE_FAMILY[tone]}
                                    />
                                );
                            })}
                        </div>
                        <div className="border-t border-border pt-2">
                            <p className="text-lg leading-none font-semibold tabular-nums text-foreground">
                                {column.n}
                            </p>
                            <p className="mt-1.5 truncate text-[11px] text-muted-foreground">
                                {band(column.key)}
                            </p>
                        </div>
                    </div>
                ))}
            </div>
        </SurfaceFrame>
    );
}

/** A slice of the relationship map. One labelled anchor; the rest is shape. */
const MAP_NODES = {
    company: { x: 214, y: 58, r: 24, kind: "company", band: null },
    you: { x: 62, y: 150, r: 17, kind: "you", band: null },
    bridge: { x: 196, y: 156, r: 15, kind: "contact", band: "warm" },
    target: { x: 336, y: 104, r: 15, kind: "contact", band: "cold" },
    peer: { x: 318, y: 196, r: 15, kind: "contact", band: "cool" },
    far: { x: 118, y: 214, r: 11, kind: "contact", band: "cool" },
} as const;

const MAP_EDGES = [
    { from: "you", to: "bridge", logged: true },
    { from: "bridge", to: "company", logged: true },
    { from: "bridge", to: "target", logged: false },
    { from: "company", to: "target", logged: true },
    { from: "company", to: "peer", logged: true },
    { from: "you", to: "far", logged: true },
    { from: "far", to: "bridge", logged: false },
] as const;

/**
 * The relationship graph, reduced to nodes and ties.
 *
 * Drawn as plain SVG rather than mounting `@xyflow/react`, which is a heavy client bundle that
 * would buy nothing on a static page. Solid ties are logged interactions and dashed ties are
 * inferred; the section copy says so, so the picture does not have to.
 */
export async function RelationMapSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceMapLabel")} sampleLabel={t("surfaceSample")}>
            <svg viewBox="0 0 400 260" className="w-full" role="img" aria-label={t("surfaceMapAlt")}>
                {MAP_EDGES.map((edge) => (
                    <line
                        key={`${edge.from}-${edge.to}`}
                        x1={MAP_NODES[edge.from].x}
                        y1={MAP_NODES[edge.from].y}
                        x2={MAP_NODES[edge.to].x}
                        y2={MAP_NODES[edge.to].y}
                        strokeWidth={edge.logged ? 1.75 : 1}
                        strokeDasharray={edge.logged ? undefined : "5 5"}
                        className={edge.logged ? "stroke-brand/45" : "stroke-border"}
                    />
                ))}
                {Object.entries(MAP_NODES).map(([key, node]) => (
                    <g key={key}>
                        <circle
                            cx={node.x}
                            cy={node.y}
                            r={node.r}
                            strokeWidth={node.kind === "you" ? 2.5 : 1.5}
                            className={cn(
                                node.kind === "company" ? "fill-muted" : "fill-card",
                                node.kind === "you" ? "stroke-brand" : "stroke-border",
                            )}
                        />
                        {node.band ? (
                            <circle
                                cx={node.x + node.r * 0.72}
                                cy={node.y - node.r * 0.72}
                                r={4.5}
                                strokeWidth={2}
                                className={cn(BAND_FILL[node.band], "stroke-card")}
                            />
                        ) : null}
                    </g>
                ))}
            </svg>
        </SurfaceFrame>
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
