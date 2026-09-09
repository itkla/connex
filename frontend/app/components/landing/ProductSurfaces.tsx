import { getTranslations } from "next-intl/server";
import {
    ArrowUpRightIcon,
    CalendarDaysIcon,
    EnvelopeIcon,
    PencilSquareIcon,
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
 * A flagged deal with the reasons underneath it.
 *
 * `DealRiskService` is deterministic — it has no dependency on `backend.ai`, so this
 * surface represents behaviour that survives an organisation switching AI off. Each row
 * is one real factor code (`close_overdue`, `stalled`, `stakeholder_cold`), and warmth
 * appears here as one contributing factor rather than as the product itself.
 */
export async function DealRiskSurface() {
    const t = await getTranslations("CommonHome");

    const factors = [
        { key: "overdue", severity: "high" as const, Icon: CalendarDaysIcon },
        { key: "quiet", severity: "high" as const, Icon: EnvelopeIcon },
        { key: "cold", severity: "medium" as const, Icon: null },
    ];

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
                    {factors.map(({ key, severity, Icon }) => (
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
                <p className="mt-1 border-t border-border pt-4 text-xs leading-relaxed text-muted-foreground">
                    {t("surfaceIntroFooter")}
                </p>
            </div>
        </SurfaceFrame>
    );
}
