import { getTranslations } from "next-intl/server";
import { RadarMark } from "@/app/components/radar/RadarVocabulary";
import { RADAR_MARK_FILL, RADAR_MARK_SHAPE } from "@/app/components/radar/radarFamilyAccent";
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

    return (
        <SurfaceFrame label={t("surfaceRadarLabel")} sampleLabel={t("surfaceSample")}>
            <div className="grid grid-cols-5 gap-2 px-4 pt-8 pb-4 sm:gap-4 sm:px-6">
                {HORIZON.map((column) => (
                    <div key={column.key} className="flex flex-col justify-end gap-3">
                        <div className="mx-auto flex h-24 w-full max-w-[6.5rem] flex-wrap-reverse content-start justify-center gap-1.5">
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
                        <div className="border-t border-border pt-2 text-center">
                            <p className="text-lg leading-none font-semibold tabular-nums text-foreground">
                                {column.n}
                            </p>
                            <p className="mt-1.5 truncate text-[11px] text-muted-foreground">
                                {t(`surfaceHorizonBand_${column.key}`)}
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
 * The signal field: Radar's glyph vocabulary at display scale.
 *
 * Shape names the family and fill names the reading, exactly as `radarFamilyAccent` defines it —
 * a circle is a cooling relationship, a diamond a deal at risk, a square an intro path. Drawing
 * them large, scattered and unevenly weighted turns the legend into a composition: the eye learns
 * the alphabet before it meets the board, and the urgent end of the field is visibly heavier.
 */
const FIELD_MARKS = [
    { x: 6, y: 62, size: 30, tone: "cold", o: 100 },
    { x: 15, y: 30, size: 20, tone: "high", o: 100 },
    { x: 21, y: 76, size: 15, tone: "medium", o: 85 },
    { x: 29, y: 46, size: 34, tone: "cool", o: 100 },
    { x: 38, y: 20, size: 13, tone: "cold", o: 70 },
    { x: 41, y: 66, size: 22, tone: "high", o: 90 },
    { x: 50, y: 40, size: 16, tone: "warm", o: 80 },
    { x: 55, y: 78, size: 12, tone: "cool", o: 60 },
    { x: 62, y: 28, size: 24, tone: "warm", o: 85 },
    { x: 69, y: 60, size: 14, tone: "low", o: 65 },
    { x: 76, y: 36, size: 18, tone: "hot", o: 75 },
    { x: 83, y: 70, size: 11, tone: "path", o: 55 },
    { x: 88, y: 22, size: 13, tone: "path", o: 60 },
    { x: 94, y: 52, size: 9, tone: "path", o: 45 },
] as const;

/**
 * Radar's marks, enlarged into a field.
 *
 * Uses the shared shape and fill maps rather than {@link RadarMark} itself, because that component
 * is deliberately fixed at the small size the product needs; here the size carries the weight.
 */
export async function SignalFieldSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceFieldLabel")} sampleLabel={t("surfaceSample")}>
            <div className="relative h-64 w-full sm:h-72">
                {FIELD_MARKS.map((mark) => (
                    <span
                        key={`${mark.x}-${mark.y}`}
                        aria-hidden
                        className={cn(
                            "absolute -translate-x-1/2 -translate-y-1/2",
                            RADAR_MARK_SHAPE[TONE_FAMILY[mark.tone]],
                            RADAR_MARK_FILL[mark.tone],
                        )}
                        style={{
                            left: `${mark.x}%`,
                            top: `${mark.y}%`,
                            width: mark.size,
                            height: mark.size,
                            opacity: mark.o / 100,
                        }}
                    />
                ))}
            </div>
        </SurfaceFrame>
    );
}
