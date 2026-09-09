import { getTranslations } from "next-intl/server";
import {
    ArrowUpRightIcon,
    CalendarDaysIcon,
    EnvelopeIcon,
    IdentificationIcon,
    PencilSquareIcon,
    TableCellsIcon,
} from "@heroicons/react/24/outline";
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

    const evidence = [
        { key: "meeting", Icon: CalendarDaysIcon },
        { key: "email", Icon: EnvelopeIcon },
        { key: "note", Icon: PencilSquareIcon },
    ] as const;

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
                    {evidence.map(({ key, Icon }) => (
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
 * Radar's ranked list: the relationships losing ground, worst first, each with the
 * single next move the product would offer.
 */
export async function RadarSurface() {
    const t = await getTranslations("CommonHome");

    const rows = [
        { key: "a", band: "cold" as const },
        { key: "b", band: "cool" as const },
        { key: "c", band: "cool" as const },
    ];

    return (
        <SurfaceFrame label={t("surfaceRadarLabel")} sampleLabel={t("surfaceSample")}>
            <ul className="divide-y divide-border">
                {rows.map((row) => (
                    <li key={row.key} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4">
                        <div className="min-w-0 flex-1">
                            <p className="truncate text-sm font-medium text-foreground">
                                {t(`surfaceRadarCompany_${row.key}`)}
                            </p>
                            <p className="truncate text-xs text-muted-foreground">
                                {t(`surfaceRadarFact_${row.key}`)}
                            </p>
                        </div>
                        <WarmthChip band={row.band} label={t(`warmthBand_${row.band}`)} />
                        <span className="inline-flex items-center gap-1 rounded-full bg-brand-light px-2.5 py-1 text-xs font-medium text-foreground">
                            {t(`surfaceRadarAction_${row.key}`)}
                            <ArrowUpRightIcon className="size-3" />
                        </span>
                    </li>
                ))}
            </ul>
        </SurfaceFrame>
    );
}

/**
 * The intro path: the shortest warm route from someone already in the workspace
 * through to a contact at a company nobody has reached yet.
 */
export async function IntroPathSurface() {
    const t = await getTranslations("CommonHome");

    const steps = [
        { key: "you", band: "hot" as const },
        { key: "bridge", band: "warm" as const },
        { key: "target", band: "cold" as const },
    ];

    return (
        <SurfaceFrame label={t("surfaceIntroLabel")} sampleLabel={t("surfaceSample")}>
            <div className="px-5 py-5">
                <ol className="space-y-0">
                    {steps.map((step, i) => (
                        <li key={step.key} className="relative flex gap-4 pb-6 last:pb-0">
                            {i < steps.length - 1 ? (
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
                <p className="mt-1 border-t border-border pt-4 text-xs text-muted-foreground">
                    {t("surfaceIntroFooter")}
                </p>
            </div>
        </SurfaceFrame>
    );
}

/**
 * One account, three colleagues. The point is not that Connex has an activity feed;
 * it is that the relationship is the workspace's rather than one person's, which is
 * what lets somebody else's history become your intro path.
 */
export async function SharedAccountSurface() {
    const t = await getTranslations("CommonHome");

    const entries = ["a", "b", "c"] as const;

    return (
        <SurfaceFrame label={t("surfaceSharedLabel")} sampleLabel={t("surfaceSample")}>
            <ul className="divide-y divide-border">
                {entries.map((key) => (
                    <li key={key} className="flex items-start gap-3 px-5 py-4">
                        <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-muted text-[11px] font-semibold text-foreground">
                            {t(`surfaceSharedInitials_${key}`)}
                        </span>
                        <div className="min-w-0 flex-1">
                            <p className="truncate text-sm text-foreground">{t(`surfaceSharedEntry_${key}`)}</p>
                            <p className="truncate text-xs text-muted-foreground">
                                {t(`surfaceSharedWho_${key}`)}
                            </p>
                        </div>
                        <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                            {t(`surfaceSharedWhen_${key}`)}
                        </span>
                    </li>
                ))}
            </ul>
        </SurfaceFrame>
    );
}

/**
 * Where a workspace's history comes from. Import and card scanning ship today;
 * the Google and Microsoft calendar/mail adapters are gated internal preview under
 * issue #868 pending provider verification, so they are labelled as preview rather
 * than presented as available.
 */
export async function SourcesSurface() {
    const t = await getTranslations("CommonHome");

    const sources = [
        { key: "csv", Icon: TableCellsIcon, preview: false },
        { key: "cards", Icon: IdentificationIcon, preview: false },
        { key: "google", Icon: CalendarDaysIcon, preview: true },
        { key: "microsoft", Icon: EnvelopeIcon, preview: true },
    ] as const;

    return (
        <div className="grid gap-3 sm:grid-cols-2">
            {sources.map(({ key, Icon, preview }) => (
                <div
                    key={key}
                    className="flex items-start gap-3 rounded-xl border border-border bg-card px-4 py-4"
                >
                    <Icon className="mt-0.5 size-5 shrink-0 text-muted-foreground" />
                    <div className="min-w-0 flex-1">
                        <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-foreground">
                            {t(`sourceName_${key}`)}
                            {preview ? (
                                <span className="rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
                                    {t("sourcePreview")}
                                </span>
                            ) : null}
                        </p>
                        <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                            {t(`sourceBody_${key}`)}
                        </p>
                    </div>
                </div>
            ))}
        </div>
    );
}
