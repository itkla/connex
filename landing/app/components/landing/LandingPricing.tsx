import Link from "next/link";
import { ArrowRightIcon, CheckIcon, MinusIcon, UserIcon, CloudIcon, BuildingOffice2Icon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import type { LandingTranslation } from "./sampleWorkspace";
import { DOCS_AVAILABLE } from "@/app/lib/deploymentSurface";

const OPTIONS = [
    { key: "free", Icon: UserIcon, highlights: ["seats", "workspaces", "records", "ask"] },
    { key: "pro", Icon: CloudIcon, highlights: ["seats", "workspaces", "records", "expansion", "ask", "clientWorkspaces"] },
    { key: "enterprise", Icon: BuildingOffice2Icon, highlights: ["sso", "hosting", "capacity", "support"] },
] as const;
type Plan = typeof OPTIONS[number]["key"];
type ComparisonValue = boolean | "one" | "two" | "five" | "recordsFree" | "recordsPro" | "smallMonthly" | "sharedPool" | "custom" | "upgrade" | "perSeat" | "addOn" | "selfService" | "productSupport" | "quoted";
type ComparisonRow = { feature: string; values: Record<Plan, ComparisonValue> };
const INCLUDED = { free: true, pro: true, enterprise: true } as const;
const COMPARISON = [
    { group: "capacity", rows: [
        { feature: "seats", values: { free: "one", pro: "two", enterprise: "custom" } },
        { feature: "extraSeats", values: { free: "upgrade", pro: "perSeat", enterprise: "custom" } },
        { feature: "records", values: { free: "recordsFree", pro: "recordsPro", enterprise: "custom" } },
        { feature: "extraRecords", values: { free: "upgrade", pro: "addOn", enterprise: "custom" } },
        { feature: "workspaces", values: { free: "one", pro: "five", enterprise: "custom" } },
        { feature: "ask", values: { free: "smallMonthly", pro: "sharedPool", enterprise: "custom" } },
    ] },
    { group: "features", rows: [
        ...["crm", "relationships", "workflows", "insights", "roles"].map((feature) => ({ feature, values: INCLUDED })),
    ] },
    { group: "services", rows: [
        { feature: "sso", values: { free: false, pro: false, enterprise: true } },
        { feature: "support", values: { free: "selfService", pro: "productSupport", enterprise: "custom" } },
    ] },
    { group: "hosting", rows: [
        { feature: "saas", values: INCLUDED },
        { feature: "silo", values: { free: false, pro: false, enterprise: "quoted" } },
        { feature: "onPrem", values: { free: false, pro: false, enterprise: "quoted" } },
    ] },
] as const satisfies readonly { group: string; rows: readonly ComparisonRow[] }[];

function FeatureStatus({ value, t }: { value: ComparisonValue; t: LandingTranslation }) {
    if (typeof value === "string") return t(`pricing.values.${value}`);
    const Icon = value ? CheckIcon : MinusIcon;
    return <><Icon aria-hidden="true" className={`ml-auto size-5 ${value ? "text-brand-dark dark:text-brand" : "text-muted-foreground"}`} /><span className="sr-only">{t(value ? "pricing.included" : "pricing.notIncluded")}</span></>;
}

/** Shows plan comparisons without prices or plan selection at signup. */
export default function LandingPricing({ t, ctaHref, ctaLabel, preLaunch = false }: { t: LandingTranslation; ctaHref: string; ctaLabel: string; preLaunch?: boolean }) {
    return (
        <>
            <div className="mt-10 grid gap-5 lg:mt-12 lg:grid-cols-3 lg:gap-y-0">
                {OPTIONS.map(({ key, Icon, highlights }) => (
                    <article key={key} aria-labelledby={`pricing-${key}-title`} className={`flex min-w-0 flex-col rounded-xl border p-6 sm:p-8 lg:grid lg:grid-rows-subgrid ${preLaunch ? "lg:row-span-4" : "lg:row-span-5"} ${key === "pro" ? "border-brand/40 bg-brand/5" : "border-border"}`}>
                        <Icon aria-hidden="true" className="size-8 text-brand-dark dark:text-brand" />
                        <h3 id={`pricing-${key}-title`} className="mt-6 text-3xl tracking-tight">{t(`pricing.${key}.name`)}</h3>
                        <p className="mt-3 text-base leading-relaxed text-muted-foreground">
                            {t(`pricing.${key}.body`)}
                            {key === "pro" && <span className="mt-3 block text-sm">{t("pricing.pro.hosting")}</span>}
                        </p>
                        <div className={`mt-7 flex-1 border-t border-border pt-6 ${preLaunch ? "" : "mb-8"}`}>
                            <p className="text-sm font-medium">{t(`pricing.${key}.includes`)}</p>
                            <ul className="mt-4 space-y-3">
                                {highlights.map((feature) => (
                                    <li key={feature} className="flex items-start gap-3 text-base leading-relaxed">
                                        <CheckIcon aria-hidden="true" className="mt-1 size-4 shrink-0 text-brand-dark dark:text-brand" />
                                        <span>{t(`pricing.${key}.highlights.${feature}`)}</span>
                                    </li>
                                ))}
                            </ul>
                        </div>
                        {!preLaunch && <Button asChild={key !== "enterprise"} disabled={key === "enterprise"} variant={key === "pro" ? "brand" : "outline"} size="page" className="h-auto min-h-11 w-full whitespace-normal px-5 py-3 text-base">
                            {key === "enterprise"
                                ? <>{t("pricing.enterprise.cta")}<ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" /></>
                                : <Link href={ctaHref}>{ctaLabel}<ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" /></Link>}
                        </Button>}
                    </article>
                ))}
            </div>
            <p className="mt-8 max-w-[75ch] text-sm leading-relaxed text-muted-foreground">{t("pricing.allFeatures")}</p>
            <div role="group" aria-label={t("pricing.compare")} aria-describedby="pricing-notes" className="mt-10 space-y-10">
                {COMPARISON.map(({ group, rows }) => (
                    <section key={group} aria-labelledby={`pricing-${group}-title`}>
                        <h3 id={`pricing-${group}-title`} className="border-t border-border px-6 pb-4 pt-6 text-lg font-semibold sm:px-8">{t(`pricing.groups.${group}`)}</h3>
                        <div className="flex flex-col gap-5 lg:grid lg:grid-cols-3 lg:gap-y-0">
                            {OPTIONS.map(({ key }) => (
                                <div key={key} data-pricing-plan={key} className={`min-w-0 border-x border-transparent px-6 sm:px-8 lg:grid lg:grid-rows-subgrid ${key === "pro" ? "bg-brand/5" : ""}`} style={{ gridRow: `span ${rows.length + 1}` }}>
                                    <h4 id={`pricing-${group}-${key}-title`} className="py-4 text-sm font-semibold">{t(`pricing.${key}.name`)}</h4>
                                    <dl aria-labelledby={`pricing-${group}-${key}-title`} className="lg:grid lg:grid-rows-subgrid" style={{ gridRow: `span ${rows.length}` }}>
                                        {rows.map(({ feature, values }) => (
                                            <div key={feature} data-pricing-feature={feature} className="flex items-start justify-between gap-4 border-t border-border/60 py-4 text-sm leading-relaxed">
                                                <dt className="min-w-0 text-muted-foreground">{t(`pricing.features.${feature}`)}{feature === "records" && <span className="mt-1 block text-xs">{t("pricing.combinedTotal")}</span>}</dt>
                                                <dd className="max-w-1/2 shrink-0 text-right font-medium"><FeatureStatus value={values[key]} t={t} /></dd>
                                            </div>
                                        ))}
                                    </dl>
                                </div>
                            ))}
                        </div>
                    </section>
                ))}
            </div>
            <div id="pricing-notes" className="mt-8 max-w-[75ch] space-y-3 text-sm leading-relaxed text-muted-foreground">
                <p>{t("pricing.poolingNote")}</p>
                <p>{t("pricing.subscriptionNote")}</p>
                <p>{t("pricing.aiNote")}{DOCS_AVAILABLE && <> <Link href="/docs/relationship-intelligence/ai-insights" className="text-foreground underline underline-offset-4">{t("pricing.aiDocs")}</Link></>}</p>
                <p>{t("pricing.usageNote")}</p>
                <p>{t("pricing.serviceNote")}</p>
            </div>
        </>
    );
}
