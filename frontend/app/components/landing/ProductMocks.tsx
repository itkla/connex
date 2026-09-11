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

const COMPANY_CONTACTS = [
    { key: "a", band: "warm" },
    { key: "b", band: "cool" },
    { key: "c", band: "cold" },
] as const;

const COMPANY_DEALS = [
    { key: "a", stage: "won" },
    { key: "b", stage: "open" },
] as const;

const STAGE_STYLE = {
    won: "bg-brand-light text-foreground ring-brand/40",
    open: "bg-muted text-foreground/75 ring-border",
} as const;

/**
 * A company record: the CRM surface everything else hangs off.
 *
 * Connex is a CRM first — companies, contacts, deals, tasks, notes — and the relationship reading is
 * an attribute of the people on the record, not a separate product. This pane exists so the page
 * shows that ordering rather than asserting it.
 */
export async function CompanyRecordMock() {
    const t = await getTranslations("CommonHome");

    return (
        <Pane label={t("mockCompanyLabel")} sampleLabel={t("mockSample")}>
            <div className="flex items-start justify-between gap-3 px-4 py-3.5">
                <div className="min-w-0">
                    <p className="truncate text-[15px] font-semibold text-foreground">{t("mockCompanyName")}</p>
                    <p className="mt-0.5 truncate text-[13px] text-muted-foreground">{t("mockCompanyMeta")}</p>
                </div>
                <span className="shrink-0 rounded-md border border-border px-2 py-1 text-xs font-medium text-foreground">
                    {t("mockCompanyAction")}
                </span>
            </div>

            <div className="border-t border-border px-4 py-3">
                <p className="text-[11px] font-medium tracking-wide text-muted-foreground uppercase">
                    {t("mockCompanyPeople")}
                </p>
                <ul className="mt-2.5 space-y-2">
                    {COMPANY_CONTACTS.map((contact) => (
                        <li key={contact.key} className="flex items-center gap-2.5">
                            <span className="min-w-0 flex-1 truncate text-[13px] text-foreground">
                                {t(`mockCompanyContact_${contact.key}`)}
                            </span>
                            <WarmthChip band={contact.band} label={t(`warmthBand_${contact.band}`)} />
                        </li>
                    ))}
                </ul>
            </div>

            <div className="border-t border-border px-4 py-3">
                <p className="text-[11px] font-medium tracking-wide text-muted-foreground uppercase">
                    {t("mockCompanyDeals")}
                </p>
                <ul className="mt-2.5 space-y-2">
                    {COMPANY_DEALS.map((deal) => (
                        <li key={deal.key} className="flex items-center gap-2.5">
                            <span className="min-w-0 flex-1 truncate text-[13px] text-foreground">
                                {t(`mockCompanyDeal_${deal.key}`)}
                            </span>
                            <span
                                className={cn(
                                    "shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset",
                                    STAGE_STYLE[deal.stage],
                                )}
                            >
                                {t(`mockCompanyStage_${deal.stage}`)}
                            </span>
                        </li>
                    ))}
                </ul>
            </div>

            <div className="flex items-center gap-2.5 border-t border-border bg-muted/30 px-4 py-3">
                <span className="size-1.5 shrink-0 rounded-full bg-brand" />
                <span className="min-w-0 flex-1 truncate text-[13px] text-foreground">{t("mockCompanyTask")}</span>
                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{t("mockCompanyTaskWhen")}</span>
            </div>
        </Pane>
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
                        <span className="shrink-0 text-[11px] font-medium text-muted-foreground">
                            {t(`mockSeverity_${factor.severity}`)}
                        </span>
                    </li>
                ))}
            </ul>
            <div className="border-t border-border px-4 py-3">
                <span className="inline-flex items-center rounded-md border border-border px-2 py-1 text-[11px] font-medium text-foreground">
                    {t("mockDealAction")}
                </span>
            </div>
        </Pane>
    );
}

const ACTIVITY_ROWS = [
    { key: "meeting", band: "warm" },
    { key: "email", band: "warm" },
    { key: "note", band: "cool" },
    { key: "deal", band: null },
] as const;

/**
 * The history on a customer, which is what you actually want before the next conversation.
 *
 * Deliberately shows a deal event in the same stream as interactions: the argument is that the
 * record is one timeline, not several tabs.
 */
export async function ActivityTimelineMock() {
    const t = await getTranslations("CommonHome");

    return (
        <Pane label={t("mockTimelineLabel")} sampleLabel={t("mockSample")}>
            <div className="px-4 py-3.5">
                <p className="truncate text-[15px] font-semibold text-foreground">{t("mockTimelineSubject")}</p>
                <p className="mt-0.5 truncate text-[13px] text-muted-foreground">{t("mockTimelineMeta")}</p>
            </div>
            <ul className="divide-y divide-border/70 border-t border-border">
                {ACTIVITY_ROWS.map((row) => (
                    <li key={row.key} className="flex items-center gap-2.5 px-4 py-2.5">
                        <span
                            className={cn(
                                "size-1.5 shrink-0 rounded-full",
                                row.band ? warmthDotClass(row.band) : "bg-brand",
                            )}
                        />
                        <span className="min-w-0 flex-1 text-[13px] text-foreground">
                            {t(`mockTimeline_${row.key}`)}
                        </span>
                        <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                            {t(`mockTimelineWhen_${row.key}`)}
                        </span>
                    </li>
                ))}
            </ul>
        </Pane>
    );
}

/**
 * The same customer after a handover: a new owner, the history unchanged.
 *
 * This is the continuity argument the page previously made with a card labelled "Employment
 * history", which described a mechanism rather than the reason it matters.
 */
export async function HandoverMock() {
    const t = await getTranslations("CommonHome");

    return (
        <Pane label={t("mockHandoverLabel")} sampleLabel={t("mockSample")}>
            <div className="flex items-center justify-between gap-3 px-4 py-3.5">
                <div className="min-w-0">
                    <p className="truncate text-[15px] font-semibold text-foreground">{t("mockCompanyName")}</p>
                    <p className="mt-0.5 truncate text-[13px] text-muted-foreground">{t("mockHandoverOwner")}</p>
                </div>
                <span className="shrink-0 rounded-full bg-brand-light px-2 py-0.5 text-[11px] font-medium text-foreground ring-1 ring-inset ring-brand/40">
                    {t("mockHandoverBadge")}
                </span>
            </div>
            <ul className="divide-y divide-border/70 border-t border-border">
                {(["history", "deals", "tasks"] as const).map((key) => (
                    <li key={key} className="flex items-center gap-2.5 px-4 py-2.5">
                        <span className="size-1.5 shrink-0 rounded-full bg-brand" />
                        <span className="min-w-0 flex-1 text-[13px] text-foreground">{t(`mockHandover_${key}`)}</span>
                    </li>
                ))}
            </ul>
        </Pane>
    );
}
