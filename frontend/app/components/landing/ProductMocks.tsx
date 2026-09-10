import { getTranslations } from "next-intl/server";
import { RadarMark } from "@/app/components/radar/RadarVocabulary";
import { warmthDotClass, warmthSurfaceClasses } from "@/app/lib/utils";
import { cn } from "@/lib/utils";

/**
 * Faithful miniatures of Connex surfaces for the landing page.
 *
 * The pattern every design-led SaaS landing page has converged on is that the product *is* the
 * visual — real interface, not icons or abstract diagrams. Connex cannot ship screenshots, because
 * the only populated instance holds real tenant data, so these are hand-built from the same
 * primitives the app uses: the product's own {@link RadarMark}, and `warmthSurfaceClasses` /
 * `warmthDotClass` for the bands. A change to the domain tokens moves the app and these together.
 *
 * Every mock carries a "Sample workspace" chip. The data is invented, and the page never implies
 * otherwise.
 */

/** Window chrome, so each mock reads as a pane of the product rather than a floating card. */
function Pane({
    label,
    sampleLabel,
    className,
    children,
}: {
    label: string;
    sampleLabel: string;
    className?: string;
    children: React.ReactNode;
}) {
    return (
        <div
            className={cn(
                "overflow-hidden rounded-xl border border-border bg-card shadow-[0_20px_60px_-40px] shadow-foreground/40",
                className,
            )}
        >
            <div className="flex items-center justify-between gap-3 border-b border-border bg-muted/40 px-4 py-2.5">
                <span className="text-[13px] font-medium text-foreground">{label}</span>
                <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium text-foreground/75">
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
                "inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset",
                warmthSurfaceClasses(band),
            )}
        >
            <span className={cn("size-1.5 shrink-0 rounded-full", warmthDotClass(band))} />
            {label}
        </span>
    );
}

const RADAR_ROWS = [
    { key: "a", tone: "cold", family: "relationship_decay", band: "cold" },
    { key: "b", tone: "high", family: "deal_risk", band: null },
    { key: "c", tone: "cool", family: "relationship_decay", band: "cool" },
    { key: "d", tone: "path", family: "warm_path", band: null },
] as const;

/** Radar's ranked list: a mark, the subject, the reading beneath it, and one action. */
export async function RadarBoardMock() {
    const t = await getTranslations("CommonHome");
    const band = await getTranslations("Radar.horizon.band");

    return (
        <Pane label={t("mockRadarLabel")} sampleLabel={t("mockSample")}>
            <div className="flex items-center gap-2 border-b border-border px-4 py-2">
                {(["overdue", "week", "month"] as const).map((key, i) => (
                    <span
                        key={key}
                        className={cn(
                            "rounded-md px-2 py-1 text-[11px] font-medium",
                            i === 0 ? "bg-brand-light text-foreground" : "text-muted-foreground",
                        )}
                    >
                        {band(key)}
                    </span>
                ))}
            </div>
            <ul className="divide-y divide-border/70">
                {RADAR_ROWS.map((row) => (
                    <li key={row.key} className="flex items-start gap-3 px-4 py-3">
                        <RadarMark tone={row.tone} family={row.family} className="mt-1" />
                        <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2">
                                <p className="truncate text-[13px] font-semibold text-foreground">
                                    {t(`mockRadarSubject_${row.key}`)}
                                </p>
                                {row.band ? <WarmthChip band={row.band} label={t(`warmthBand_${row.band}`)} /> : null}
                            </div>
                            <p className="mt-0.5 truncate text-xs text-muted-foreground">
                                {t(`mockRadarReading_${row.key}`)}
                            </p>
                        </div>
                        <span className="mt-0.5 shrink-0 rounded-md border border-border px-2 py-1 text-[11px] font-medium text-foreground">
                            {t(`mockRadarAction_${row.key}`)}
                        </span>
                    </li>
                ))}
            </ul>
        </Pane>
    );
}

const TIMELINE = [
    { key: "meeting", band: "warm" },
    { key: "email", band: "warm" },
    { key: "note", band: "cool" },
] as const;

/** A contact record: the reading, the employment history behind it, and the activity feed. */
export async function ContactRecordMock() {
    const t = await getTranslations("CommonHome");

    return (
        <Pane label={t("mockContactLabel")} sampleLabel={t("mockSample")}>
            <div className="flex items-start justify-between gap-3 px-4 py-3.5">
                <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-foreground">{t("mockContactName")}</p>
                    <p className="mt-0.5 truncate text-xs text-muted-foreground">{t("mockContactRole")}</p>
                </div>
                <WarmthChip band="cool" label={t("warmthBand_cool")} />
            </div>
            <div className="border-t border-border px-4 py-3">
                <p className="text-[10px] font-medium tracking-wide text-muted-foreground uppercase">
                    {t("mockContactHistoryHeading")}
                </p>
                <div className="mt-2 flex items-center gap-1.5 text-[11px]">
                    <span className="rounded border border-border bg-muted px-1.5 py-0.5 text-foreground/75">
                        {t("mockContactPrev")}
                    </span>
                    <span className="h-px flex-1 bg-border" />
                    <span className="rounded border border-brand/40 bg-brand-light px-1.5 py-0.5 font-medium text-foreground">
                        {t("mockContactNow")}
                    </span>
                </div>
            </div>
            <ul className="divide-y divide-border/70 border-t border-border">
                {TIMELINE.map((row) => (
                    <li key={row.key} className="flex items-center gap-2.5 px-4 py-2.5">
                        <span className={cn("size-1.5 shrink-0 rounded-full", warmthDotClass(row.band))} />
                        <span className="min-w-0 flex-1 truncate text-xs text-foreground">
                            {t(`mockActivity_${row.key}`)}
                        </span>
                        <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground">
                            {t(`mockActivityWhen_${row.key}`)}
                        </span>
                    </li>
                ))}
            </ul>
        </Pane>
    );
}

const DEAL_FACTORS = [
    { key: "overdue", severity: "high" },
    { key: "quiet", severity: "medium" },
    { key: "cold", severity: "medium" },
] as const;

const SEVERITY_DOT = { high: "bg-risk-high", medium: "bg-risk-medium" } as const;

/** A deal with its risk factors — the shape `DealRiskService` actually produces. */
export async function DealRiskMock() {
    const t = await getTranslations("CommonHome");

    return (
        <Pane label={t("mockDealLabel")} sampleLabel={t("mockSample")}>
            <div className="flex items-start justify-between gap-3 px-4 py-3.5">
                <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-foreground">{t("mockDealName")}</p>
                    <p className="mt-0.5 truncate text-xs tabular-nums text-muted-foreground">{t("mockDealMeta")}</p>
                </div>
                <span className="shrink-0 rounded-full bg-risk-high/15 px-2 py-0.5 text-[11px] font-medium text-foreground ring-1 ring-inset ring-risk-high/40">
                    {t("mockDealBadge")}
                </span>
            </div>
            <ul className="divide-y divide-border/70 border-t border-border">
                {DEAL_FACTORS.map((factor) => (
                    <li key={factor.key} className="flex items-center gap-2.5 px-4 py-2.5">
                        <span className={cn("size-1.5 shrink-0 rounded-full", SEVERITY_DOT[factor.severity])} />
                        <span className="min-w-0 flex-1 truncate text-xs text-foreground">
                            {t(`mockDealFactor_${factor.key}`)}
                        </span>
                    </li>
                ))}
            </ul>
        </Pane>
    );
}
