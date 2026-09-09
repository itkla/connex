import { getTranslations } from "next-intl/server";
import {
    ArrowUpRightIcon,
    CalendarDaysIcon,
    EnvelopeIcon,
    PencilSquareIcon,
} from "@heroicons/react/24/outline";
import { RadarMark } from "@/app/components/radar/RadarVocabulary";
import { warmthDotClass, warmthSurfaceClasses } from "@/app/lib/utils";
import { cn } from "@/lib/utils";

/**
 * Faithful miniatures of real Connex surfaces for the landing page.
 *
 * These are not screenshots and not decorative diagrams. They render with the
 * product's own warmth helpers (`warmthSurfaceClasses`, `warmthDotClass`), so a
 * band shown here is the same colour it is inside the app, and a change to the
 * domain tokens moves both together.
 *
 * Action pills use `text-foreground` on the tinted brand surface rather than
 * `text-brand-dark`, which measured 3.6:1 against it and failed AA for 12px text.
 *
 * The data is illustrative and every surface says so in its own chrome, because
 * staging holds real tenant data and a marketing page must not imply customers
 * that do not exist yet.
 */

/**
 * Radar's five deadline columns, in the order `RADAR_HORIZON_BANDS` declares them. Mark shape
 * names the family and fill names the reading, matching `radarFamilyAccent`.
 */
const HORIZON_COLUMNS = [
    {
        key: "overdue",
        marks: [
            { tone: "cold", family: "relationship_decay" },
            { tone: "high", family: "deal_risk" },
            { tone: "cold", family: "relationship_decay" },
            { tone: "cool", family: "relationship_decay" },
            { tone: "high", family: "deal_risk" },
            { tone: "cold", family: "relationship_decay" },
            { tone: "medium", family: "deal_risk" },
        ],
    },
    {
        key: "week",
        marks: [
            { tone: "cool", family: "relationship_decay" },
            { tone: "high", family: "deal_risk" },
            { tone: "medium", family: "deal_risk" },
            { tone: "cool", family: "relationship_decay" },
            { tone: "cold", family: "relationship_decay" },
            { tone: "cool", family: "relationship_decay" },
            { tone: "warm", family: "relationship_decay" },
            { tone: "medium", family: "deal_risk" },
            { tone: "cool", family: "relationship_decay" },
            { tone: "cold", family: "relationship_decay" },
            { tone: "high", family: "deal_risk" },
            { tone: "cool", family: "relationship_decay" },
            { tone: "warm", family: "relationship_decay" },
            { tone: "low", family: "deal_risk" },
        ],
    },
    {
        key: "month",
        marks: [
            { tone: "cool", family: "relationship_decay" },
            { tone: "medium", family: "deal_risk" },
            { tone: "warm", family: "relationship_decay" },
            { tone: "cool", family: "relationship_decay" },
            { tone: "warm", family: "relationship_decay" },
            { tone: "low", family: "deal_risk" },
            { tone: "cool", family: "relationship_decay" },
            { tone: "warm", family: "relationship_decay" },
            { tone: "medium", family: "deal_risk" },
        ],
    },
    {
        key: "later",
        marks: [
            { tone: "warm", family: "relationship_decay" },
            { tone: "low", family: "deal_risk" },
            { tone: "warm", family: "relationship_decay" },
            { tone: "hot", family: "relationship_decay" },
            { tone: "low", family: "deal_risk" },
        ],
    },
    {
        key: "undated",
        marks: [
            { tone: "path", family: "warm_path" },
            { tone: "path", family: "warm_path" },
            { tone: "path", family: "warm_path" },
            { tone: "path", family: "warm_path" },
            { tone: "path", family: "warm_path" },
            { tone: "path", family: "warm_path" },
        ],
    },
] as const;

/** One example of each family, so the glyph vocabulary is legible without opening the product. */
const HORIZON_LEGEND = [
    { family: "relationship_decay", tone: "cool" },
    { family: "deal_risk", tone: "high" },
    { family: "warm_path", tone: "path" },
] as const;

/** SVG fill per warmth band. `warmthDotClass` returns a `bg-*` utility, which an SVG cannot use. */
const MAP_BAND_FILL = {
    hot: "[fill:var(--warmth-hot)]",
    warm: "[fill:var(--warmth-warm)]",
    cool: "[fill:var(--warmth-cool)]",
    cold: "[fill:var(--warmth-cold)]",
} as const;

/** A slice of the relationship map. `you` is a colleague; the rest are CRM records. */
const MAP_NODES = {
    company: { x: 210, y: 62, role: "company", band: null },
    you: { x: 74, y: 168, role: "you", band: null },
    bridge: { x: 210, y: 178, role: "contact", band: "warm" },
    target: { x: 340, y: 120, role: "contact", band: "cold" },
    peer: { x: 330, y: 214, role: "contact", band: "cool" },
} as const;

const MAP_EDGES = [
    { from: "you", to: "bridge", strong: true },
    { from: "bridge", to: "company", strong: true },
    { from: "bridge", to: "target", strong: false },
    { from: "company", to: "target", strong: true },
    { from: "company", to: "peer", strong: true },
] as const;

const EVIDENCE_ROWS = [
    { key: "meeting", Icon: CalendarDaysIcon },
    { key: "email", Icon: EnvelopeIcon },
    { key: "note", Icon: PencilSquareIcon },
] as const;

/**
 * Severities mirror `DealRiskService`: `close_overdue` is HIGH, `stalled` is MEDIUM, and
 * `closing_soon_quiet` (the HIGH staleness variant) cannot fire once the close date has passed.
 */
const RISK_FACTORS = [
    { key: "overdue", severity: "high", Icon: CalendarDaysIcon },
    { key: "quiet", severity: "medium", Icon: EnvelopeIcon },
    { key: "cold", severity: "medium", Icon: null },
] as const;

/**
 * A warm path is bridge to target. `WarmPathService` ranks a contact the team is already warm
 * with as the bridge; there is no teammate node, so the miniature does not draw one.
 */
const INTRO_STEPS = [
    { key: "bridge", band: "warm" },
    { key: "target", band: "cold" },
] as const;

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

function WarmthChip({ band, label }: { band: "hot" | "warm" | "cool" | "cold"; label: string }) {
    return (
        <span
            className={cn(
                "inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
                warmthSurfaceClasses(band),
            )}
        >
            <span className={cn("size-2 shrink-0 rounded-full", warmthDotClass(band))} />
            {label}
        </span>
    );
}

/**
 * A warmth reading with the interactions underneath it. This is the surface that
 * substantiates the page's central claim: a reading always shows its evidence.
 */
export async function WarmthReadingSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceWarmthLabel")} sampleLabel={t("surfaceSample")}>
            <div className="flex flex-wrap items-center justify-between gap-4 px-5 py-5">
                <div className="min-w-0">
                    <p className="truncate text-base font-semibold text-foreground">{t("surfaceContactName")}</p>
                    <p className="truncate text-sm text-muted-foreground">{t("surfaceContactRole")}</p>
                </div>
                <WarmthChip band="cool" label={t("warmthBand_cool")} />
            </div>
            <div className="border-t border-border bg-muted/40 px-5 py-4">
                <p className="text-xs font-medium text-muted-foreground">{t("surfaceEvidenceHeading")}</p>
                <ul className="mt-3 space-y-2.5">
                    {EVIDENCE_ROWS.map(({ key, Icon }) => (
                        <li key={key} className="flex items-center gap-3 text-sm">
                            <Icon className="size-4 shrink-0 text-muted-foreground" />
                            <span className="min-w-0 flex-1 truncate text-foreground">{t(`surfaceEvidence_${key}`)}</span>
                            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                {t(`surfaceEvidenceWhen_${key}`)}
                            </span>
                        </li>
                    ))}
                </ul>
            </div>
        </SurfaceFrame>
    );
}

/**
 * A flagged deal with the reasons underneath it.
 *
 * `DealRiskService` is deterministic — it has no dependency on `backend.ai`, so this
 * surface represents behaviour that survives an organisation switching AI off. Each row
 * is one real factor code (`close_overdue`, `stalled`, `stakeholder_cold`), and warmth
 * appears here as one contributing factor rather than as the product itself.
 */
export async function DealRiskSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceRiskLabel")} sampleLabel={t("surfaceSample")}>
            <div className="flex flex-wrap items-start justify-between gap-3 px-5 py-5">
                <div className="min-w-0">
                    <p className="truncate text-base font-semibold text-foreground">{t("surfaceRiskDealName")}</p>
                    <p className="mt-0.5 truncate text-sm tabular-nums text-muted-foreground">
                        {t("surfaceRiskDealMeta")}
                    </p>
                </div>
                <span className="shrink-0 rounded-full bg-warmth-cold/15 px-2.5 py-1 text-xs font-medium text-foreground ring-1 ring-inset ring-warmth-cold/40">
                    {t("surfaceRiskBadge")}
                </span>
            </div>
            <div className="border-t border-border bg-muted/40 px-5 py-4">
                <p className="text-xs font-medium text-muted-foreground">{t("surfaceRiskFactorsHeading")}</p>
                <ul className="mt-3 space-y-2.5">
                    {RISK_FACTORS.map(({ key, severity, Icon }) => (
                        <li key={key} className="flex items-center gap-3 text-sm">
                            {Icon ? (
                                <Icon className="size-4 shrink-0 text-muted-foreground" />
                            ) : (
                                <span className={cn("size-4 shrink-0 rounded-full", warmthDotClass("cool"))} />
                            )}
                            <span className="min-w-0 flex-1 text-foreground">{t(`surfaceRiskFactor_${key}`)}</span>
                            <span className="shrink-0 text-xs font-medium text-muted-foreground">
                                {t(`surfaceRiskSeverity_${severity}`)}
                            </span>
                        </li>
                    ))}
                </ul>
                <span className="mt-4 inline-flex items-center gap-1 rounded-full bg-brand-light px-2.5 py-1 text-xs font-medium text-foreground">
                    {t("surfaceRiskAction")}
                    <ArrowUpRightIcon className="size-3" />
                </span>
            </div>
        </SurfaceFrame>
    );
}

/**
 * An intro path, with the basis for every hop shown alongside it.
 *
 * `WarmPathService` distinguishes recorded interactions from inferred employer
 * overlap, and the surface preserves that: a shared employer is a weaker signal
 * than logged contact, and the footer says so. Presenting both as one undifferentiated
 * "connection" would overstate what the product knows.
 */
export async function IntroPathSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceIntroLabel")} sampleLabel={t("surfaceSample")}>
            <div className="px-5 py-5">
                <ol className="space-y-0">
                    {INTRO_STEPS.map((step, i) => (
                        <li key={step.key} className="relative flex gap-4 pb-6 last:pb-0">
                            {i < INTRO_STEPS.length - 1 ? (
                                <span
                                    aria-hidden="true"
                                    className="absolute left-[11px] top-6 h-[calc(100%-1.5rem)] w-px bg-linear-to-b from-brand/60 to-border"
                                />
                            ) : null}
                            <span
                                className={cn(
                                    "relative mt-0.5 size-6 shrink-0 rounded-full ring-4 ring-card",
                                    warmthDotClass(step.band),
                                )}
                            />
                            <div className="min-w-0 flex-1">
                                <p className="truncate text-sm font-medium text-foreground">
                                    {t(`surfaceIntroName_${step.key}`)}
                                </p>
                                <p className="truncate text-xs text-muted-foreground">
                                    {t(`surfaceIntroRole_${step.key}`)}
                                </p>
                            </div>
                        </li>
                    ))}
                </ol>
                <p className="mt-1 border-t border-border pt-4 text-xs leading-relaxed text-muted-foreground">
                    {t("surfaceIntroFooter")}
                </p>
            </div>
        </SurfaceFrame>
    );
}

/**
 * Radar's horizon board: every flagged signal placed on the axis of when it starts costing you.
 *
 * This renders the product's own {@link RadarMark}, so the glyph vocabulary is identical to the
 * app — circle for a cooling relationship, diamond for a deal at risk, square for an intro path,
 * filled by warmth band. `radarHorizon.ts` defines the five columns and states that the deadline
 * is "the one fact no other surface in the product can assemble across families", which is exactly
 * the claim this surface is here to make.
 */
export async function RadarHorizonSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceRadarLabel")} sampleLabel={t("surfaceSample")}>
            <div className="grid grid-cols-5 gap-1 bg-muted/40 p-1">
                {HORIZON_COLUMNS.map((column) => (
                    <div key={column.key} className="flex flex-col justify-end gap-2 rounded-xl px-2 pt-3 pb-2">
                        <div className="flex h-20 flex-wrap-reverse content-start gap-1 overflow-hidden">
                            {column.marks.map((mark, i) => (
                                <RadarMark key={`${column.key}-${i}`} tone={mark.tone} family={mark.family} />
                            ))}
                        </div>
                        <div className="border-t border-border pt-1.5">
                            <p className="text-base leading-none font-semibold tabular-nums text-foreground">
                                {column.marks.length}
                            </p>
                            <p className="mt-1 truncate text-xs text-muted-foreground">
                                {t(`surfaceHorizonBand_${column.key}`)}
                            </p>
                        </div>
                    </div>
                ))}
            </div>
            <ul className="flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border px-5 py-3">
                {HORIZON_LEGEND.map((entry) => (
                    <li key={entry.family} className="flex items-center gap-2 text-xs text-muted-foreground">
                        <RadarMark tone={entry.tone} family={entry.family} />
                        {t(`surfaceHorizonLegend_${entry.family}`)}
                    </li>
                ))}
            </ul>
        </SurfaceFrame>
    );
}

/**
 * A slice of the relationship map: who the workspace knows, and through whom.
 *
 * Drawn as plain SVG rather than mounting `@xyflow/react`, which is a heavy client bundle and
 * would buy nothing on a static marketing page. Node roles follow the real map's vocabulary —
 * company, contact, and a colleague on your team — and edges are tinted by the warmth of the tie.
 */
export async function RelationMapSurface() {
    const t = await getTranslations("CommonHome");

    return (
        <SurfaceFrame label={t("surfaceMapLabel")} sampleLabel={t("surfaceSample")}>
            <div className="relative">
                <svg viewBox="0 0 420 250" className="w-full" role="img" aria-label={t("surfaceMapAlt")}>
                    {MAP_EDGES.map((edge) => (
                        <line
                            key={`${edge.from}-${edge.to}`}
                            x1={MAP_NODES[edge.from].x}
                            y1={MAP_NODES[edge.from].y}
                            x2={MAP_NODES[edge.to].x}
                            y2={MAP_NODES[edge.to].y}
                            strokeWidth={edge.strong ? 2 : 1}
                            strokeDasharray={edge.strong ? undefined : "4 4"}
                            className={edge.strong ? "stroke-brand/50" : "stroke-border"}
                        />
                    ))}
                    {Object.entries(MAP_NODES).map(([key, node]) => (
                        <g key={key}>
                            <circle
                                cx={node.x}
                                cy={node.y}
                                r={node.role === "company" ? 21 : 15}
                                className={cn(
                                    node.role === "company" ? "fill-muted stroke-border" : "fill-card",
                                    node.role === "you" ? "stroke-brand" : "stroke-border",
                                )}
                                strokeWidth={node.role === "you" ? 2.5 : 1.5}
                            />
                            {node.band ? (
                                <circle
                                    cx={node.x + 11}
                                    cy={node.y - 11}
                                    r={4.5}
                                    className={cn(MAP_BAND_FILL[node.band], "stroke-card")}
                                    strokeWidth={2}
                                />
                            ) : null}
                            <text
                                x={node.x}
                                y={node.y + (node.role === "company" ? 38 : 32)}
                                textAnchor="middle"
                                className="fill-foreground text-[11px] font-medium"
                            >
                                {t(`surfaceMapNode_${key}`)}
                            </text>
                        </g>
                    ))}
                </svg>
            </div>
            <p className="border-t border-border px-5 py-3 text-xs leading-relaxed text-muted-foreground">
                {t("surfaceMapFooter")}
            </p>
        </SurfaceFrame>
    );
}
