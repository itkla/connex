import Link from "next/link";
import { ArrowRightIcon, CheckIcon, MinusIcon, UserIcon, CloudIcon, BuildingOffice2Icon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import type { LandingTranslation } from "./sampleWorkspace";

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
    return <><Icon aria-hidden="true" className={`mx-auto size-5 ${value ? "text-brand-dark dark:text-brand" : "text-muted-foreground"}`} /><span className="sr-only">{t(value ? "pricing.included" : "pricing.notIncluded")}</span></>;
}

/** Shows plan comparisons without prices or plan selection at signup. */
export default function LandingPricing({ t, ctaHref, ctaLabel }: { t: LandingTranslation; ctaHref: string; ctaLabel: string }) {
    return (
        <>
            <div className="mt-10 grid gap-5 lg:mt-12 lg:grid-cols-3 lg:gap-y-0">
                {OPTIONS.map(({ key, Icon, highlights }) => (
                    <article key={key} className={`flex min-w-0 flex-col rounded-xl border p-6 sm:p-8 lg:row-span-5 lg:grid lg:grid-rows-subgrid ${key === "pro" ? "border-brand/40 bg-brand/5" : "border-border"}`}>
                        <Icon aria-hidden="true" className="size-8 text-brand-dark dark:text-brand" />
                        <h3 className="mt-6 text-3xl tracking-tight">{t(`pricing.${key}.name`)}</h3>
                        <p className="mt-3 text-base leading-relaxed text-muted-foreground">
                            {t(`pricing.${key}.body`)}
                            {key === "pro" && <span className="mt-3 block text-sm">{t("pricing.pro.hosting")}</span>}
                        </p>
                        <div className="mb-8 mt-7 flex-1 border-t border-border pt-6">
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
                        <Button asChild={key !== "enterprise"} disabled={key === "enterprise"} variant={key === "pro" ? "brand" : "outline"} size="page" className="h-auto min-h-11 w-full whitespace-normal px-5 py-3 text-base">
                            {key === "enterprise"
                                ? <>{t("pricing.enterprise.cta")}<ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" /></>
                                : <Link href={ctaHref}>{ctaLabel}<ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" /></Link>}
                        </Button>
                    </article>
                ))}
            </div>
            <div id="pricing-pooling-note" className="mt-8 max-w-[85ch] space-y-3 text-base leading-relaxed text-muted-foreground">
                <p>{t("pricing.poolingNote")}</p>
                <p>{t("pricing.subscriptionNote")}</p>
            </div>
            <div className="mt-16 sm:mt-20">
                <h3 id="pricing-comparison-title" className="text-3xl tracking-tight sm:text-4xl">{t("pricing.compare")}</h3>
                <p className="mt-4 max-w-[70ch] text-base leading-relaxed text-muted-foreground sm:text-lg">{t("pricing.allFeatures")}</p>
                <table aria-labelledby="pricing-comparison-title" aria-describedby="pricing-pooling-note pricing-ai-note pricing-service-note" className="mt-8 w-full table-fixed border-collapse text-sm sm:text-base">
                    <colgroup><col className="w-1/3 sm:w-2/5" /><col /><col /><col /></colgroup>
                    <thead>
                        <tr className="border-b border-border">
                            <th scope="col" className="pb-4 pr-3 text-left font-medium">{t("pricing.feature")}</th>
                            {OPTIONS.map(({ key }) => <th key={key} scope="col" className={`px-1 pb-4 text-center text-xs font-semibold sm:text-base ${key === "pro" ? "bg-brand/5" : ""}`}>{t(`pricing.${key}.name`)}</th>)}
                        </tr>
                    </thead>
                    {COMPARISON.map(({ group, rows }) => (
                        <tbody key={group}>
                            <tr><th scope="rowgroup" colSpan={4} className="border-b border-border bg-muted/50 px-3 py-3 text-left text-sm font-semibold">{t(`pricing.groups.${group}`)}</th></tr>
                            {rows.map(({ feature, values }) => (
                                <tr key={feature} data-pricing-feature={feature} className="border-b border-border">
                                    <th scope="row" className="py-4 pr-3 text-left font-normal leading-relaxed">{t(`pricing.features.${feature}`)}{feature === "records" && <span className="mt-1 block text-xs text-muted-foreground">{t("pricing.combinedTotal")}</span>}</th>
                                    {OPTIONS.map(({ key }) => <td key={key} data-pricing-plan={key} className={`px-1 py-4 text-center text-xs leading-relaxed sm:text-base ${key === "pro" ? "bg-brand/5" : ""}`}><FeatureStatus value={values[key]} t={t} /></td>)}
                                </tr>
                            ))}
                        </tbody>
                    ))}
                </table>
                <p id="pricing-ai-note" className="mt-5 max-w-[75ch] text-sm leading-relaxed text-muted-foreground">{t("pricing.aiNote")} <Link href="/docs/relationship-intelligence/ai-insights" className="text-foreground underline underline-offset-4">{t("pricing.aiDocs")}</Link></p>
                <p className="mt-3 max-w-[75ch] text-sm leading-relaxed text-muted-foreground">{t("pricing.usageNote")}</p>
                <p id="pricing-service-note" className="mt-3 max-w-[75ch] text-sm leading-relaxed text-muted-foreground">{t("pricing.serviceNote")}</p>
            </div>
        </>
    );
}
