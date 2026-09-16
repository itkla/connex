import Link from "next/link";
import { ArrowRightIcon, CheckIcon, MinusIcon, UserIcon, CloudIcon, BuildingOffice2Icon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import type { LandingTranslation } from "./sampleWorkspace";
import { DOCS_AVAILABLE } from "@/app/lib/deploymentSurface";

const OPTIONS = [
    { key: "free", Icon: UserIcon },
    { key: "pro", Icon: CloudIcon },
    { key: "enterprise", Icon: BuildingOffice2Icon },
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
const PLAN_ROW_COUNT = 1 + COMPARISON.reduce((total, { rows }) => total + rows.length + 1, 0);

function FeatureStatus({ value, t }: { value: ComparisonValue; t: LandingTranslation }) {
    if (typeof value === "string") return t(`pricing.values.${value}`);
    const Icon = value ? CheckIcon : MinusIcon;
    return <><Icon aria-hidden="true" className={`ml-auto size-5 ${value ? "text-brand-dark dark:text-brand" : "text-muted-foreground"}`} /><span className="sr-only">{t(value ? "pricing.included" : "pricing.notIncluded")}</span></>;
}

/** Shows plan comparisons without prices or plan selection at signup. */
export default function LandingPricing({ t, ctaHref, ctaLabel, preLaunch = false }: { t: LandingTranslation; ctaHref: string; ctaLabel: string; preLaunch?: boolean }) {
    return (
        <>
            <div className="mt-10 flex flex-col gap-5 lg:mt-12 lg:grid lg:grid-cols-3 lg:gap-y-0">
                {OPTIONS.map(({ key, Icon }) => (
                    <article key={key} aria-labelledby={`pricing-${key}-title`} style={{ gridRow: `span ${PLAN_ROW_COUNT}` }} className={`flex min-w-0 flex-col rounded-xl border p-6 sm:p-8 lg:grid lg:grid-rows-subgrid ${key === "pro" ? "border-brand/40 bg-brand/5" : "border-border"}`}>
                        <header className="flex flex-col">
                            <Icon aria-hidden="true" className="size-8 text-brand-dark dark:text-brand" />
                            <h3 id={`pricing-${key}-title`} className="mt-6 text-3xl tracking-tight">{t(`pricing.${key}.name`)}</h3>
                            <div className="mt-3 flex-1 space-y-3 text-base leading-relaxed text-muted-foreground">
                                <p>{t(`pricing.${key}.body`)}</p>
                                {key === "pro" && <p className="text-sm">{t("pricing.pro.hosting")}<br />{t("pricing.pro.highlights.clientWorkspaces")}</p>}
                            </div>
                            {!preLaunch && <Button asChild={key !== "enterprise"} disabled={key === "enterprise"} variant={key === "pro" ? "brand" : "outline"} size="page" className="mt-7 h-auto min-h-11 w-full whitespace-normal px-5 py-3 text-base">
                                {key === "enterprise"
                                    ? <>{t("pricing.enterprise.cta")}<ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" /></>
                                    : <Link href={ctaHref}>{ctaLabel}<ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" /></Link>}
                            </Button>}
                        </header>
                        {COMPARISON.map(({ group, rows }) => (
                            <section key={group} aria-labelledby={`pricing-${key}-${group}`} style={{ gridRow: `span ${rows.length + 1}` }} className="mt-7 border-t border-border lg:grid lg:grid-rows-subgrid">
                                <h4 id={`pricing-${key}-${group}`} className="pb-2 pt-5 text-sm font-semibold">{t(`pricing.groups.${group}`)}</h4>
                                <dl style={{ gridRow: `span ${rows.length}` }} className="lg:grid lg:grid-rows-subgrid">
                                    {rows.map(({ feature, values }) => (
                                        <div key={feature} data-pricing-feature={feature} className="grid grid-cols-[minmax(0,1fr)_minmax(0,0.75fr)] items-start gap-3 border-b border-border/60 py-3 text-sm leading-relaxed last:border-b-0">
                                            <dt className="text-muted-foreground">{t(`pricing.features.${feature}`)}{feature === "records" && <span className="mt-1 block text-xs">{t("pricing.combinedTotal")}</span>}</dt>
                                            <dd data-pricing-plan={key} className="text-right font-medium"><FeatureStatus value={values[key]} t={t} /></dd>
                                        </div>
                                    ))}
                                </dl>
                            </section>
                        ))}
                    </article>
                ))}
            </div>
            <div id="pricing-notes" className="mt-8 max-w-[85ch] space-y-3 text-sm leading-relaxed text-muted-foreground">
                <p>{t("pricing.allFeatures")}</p>
                <p>{t("pricing.poolingNote")}</p>
                <p>{t("pricing.subscriptionNote")}</p>
                <p>{t("pricing.aiNote")}{DOCS_AVAILABLE && <> <Link href="/docs/relationship-intelligence/ai-insights" className="text-foreground underline underline-offset-4">{t("pricing.aiDocs")}</Link></>}</p>
                <p>{t("pricing.usageNote")}</p>
                <p>{t("pricing.serviceNote")}</p>
            </div>
        </>
    );
}
