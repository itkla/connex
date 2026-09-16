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

function FeatureStatus({ value, t }: { value: ComparisonValue; t: LandingTranslation }) {
    if (typeof value === "string") return t(`pricing.values.${value}`);
    const Icon = value ? CheckIcon : MinusIcon;
    return <><Icon aria-hidden="true" className={`mx-auto size-5 ${value ? "text-brand-dark dark:text-brand" : "text-muted-foreground"}`} /><span className="sr-only">{t(value ? "pricing.included" : "pricing.notIncluded")}</span></>;
}

/** Shows plan comparisons without prices or plan selection at signup. */
export default function LandingPricing({ t, ctaHref, ctaLabel, preLaunch = false }: { t: LandingTranslation; ctaHref: string; ctaLabel: string; preLaunch?: boolean }) {
    return (
        <>
            <div className="mt-10 grid gap-y-5 lg:mt-12 lg:grid-cols-4">
                <p className="hidden self-end pb-6 pr-6 text-sm leading-relaxed text-muted-foreground lg:block">{t("pricing.allFeatures")}</p>
                {OPTIONS.map(({ key, Icon }) => (
                    <article key={key} aria-labelledby={`pricing-${key}-title`} className={`flex min-w-0 flex-col rounded-xl border p-6 lg:mx-2.5 ${key === "pro" ? "border-brand/40 bg-brand/5" : "border-border"}`}>
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
                    </article>
                ))}
            </div>
            <p className="mt-8 text-sm leading-relaxed text-muted-foreground lg:hidden">{t("pricing.allFeatures")}</p>
            <table className="mt-6 w-full table-fixed border-collapse text-sm">
                <caption className="sr-only">{t("pricing.compare")}</caption>
                <colgroup><col className="w-1/3 lg:w-1/4" /><col /><col /><col /></colgroup>
                <thead>
                    <tr>
                        <th scope="col" className="pb-4 pr-3 text-left font-medium lg:p-0"><span className="lg:sr-only">{t("pricing.feature")}</span></th>
                        {OPTIONS.map(({ key }) => <th key={key} scope="col" className="px-1 pb-4 text-center text-xs font-semibold sm:text-sm lg:p-0"><span className="lg:sr-only">{t(`pricing.${key}.name`)}</span></th>)}
                    </tr>
                </thead>
                {COMPARISON.map(({ group, rows }) => (
                    <tbody key={group}>
                        <tr><th scope="rowgroup" colSpan={4} className="border-y border-border bg-muted/50 px-3 py-3 text-left font-semibold">{t(`pricing.groups.${group}`)}</th></tr>
                        {rows.map(({ feature, values }) => (
                            <tr key={feature} data-pricing-feature={feature} className="border-b border-border/60">
                                <th scope="row" className="py-4 pr-3 text-left font-normal leading-relaxed text-muted-foreground">{t(`pricing.features.${feature}`)}{feature === "records" && <span className="mt-1 block text-xs">{t("pricing.combinedTotal")}</span>}</th>
                                {OPTIONS.map(({ key }) => <td key={key} data-pricing-plan={key} className={`px-1 py-4 text-center text-xs font-medium leading-relaxed sm:text-sm ${key === "pro" ? "bg-brand/5" : ""}`}><FeatureStatus value={values[key]} t={t} /></td>)}
                            </tr>
                        ))}
                    </tbody>
                ))}
            </table>
            <div id="pricing-notes" className="mt-8 max-w-[85ch] space-y-3 text-sm leading-relaxed text-muted-foreground">
                <p>{t("pricing.poolingNote")}</p>
                <p>{t("pricing.subscriptionNote")}</p>
                <p>{t("pricing.aiNote")}{DOCS_AVAILABLE && <> <Link href="/docs/relationship-intelligence/ai-insights" className="text-foreground underline underline-offset-4">{t("pricing.aiDocs")}</Link></>}</p>
                <p>{t("pricing.usageNote")}</p>
                <p>{t("pricing.serviceNote")}</p>
            </div>
        </>
    );
}
