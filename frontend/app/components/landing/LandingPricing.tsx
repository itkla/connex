import Link from "next/link";
import { ArrowRightIcon, CheckIcon, CloudIcon, ServerStackIcon, BuildingOffice2Icon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import type { LandingTranslation } from "./sampleWorkspace";

const OPTIONS = [
    { key: "cloud", Icon: CloudIcon },
    { key: "dedicated", Icon: ServerStackIcon },
    { key: "selfHosted", Icon: BuildingOffice2Icon },
] as const;

/** Compares documented deployment options without implying prices or commercial entitlements. */
export default function LandingPricing({ t, ctaHref, ctaLabel }: { t: LandingTranslation; ctaHref: string; ctaLabel: string }) {
    return (
        <div className="mt-10 grid gap-5 lg:mt-12 lg:grid-cols-3 lg:gap-y-0">
            {OPTIONS.map(({ key, Icon }) => (
                <article key={key} className={`flex min-w-0 flex-col rounded-xl border p-6 sm:p-8 lg:row-span-5 lg:grid lg:grid-rows-subgrid ${key === "cloud" ? "border-brand/40 bg-brand/5" : "border-border"}`}>
                    <Icon aria-hidden="true" className="size-8 text-brand-dark dark:text-brand" />
                    <h3 className="mt-6 text-3xl tracking-tight">{t(`pricing.${key}.name`)}</h3>
                    <p className="mt-3 text-base leading-relaxed text-muted-foreground">{t(`pricing.${key}.body`)}</p>
                    <ul className="mb-8 mt-7 flex-1 space-y-3 border-t border-border pt-6">
                        {(["hosting", "operation", "control"] as const).map((detail) => (
                            <li key={detail} className="flex items-start gap-3 text-base leading-relaxed">
                                <CheckIcon aria-hidden="true" className="mt-1 size-4 shrink-0 text-brand-dark dark:text-brand" />
                                <span>{t(`pricing.${key}.${detail}`)}</span>
                            </li>
                        ))}
                    </ul>
                    <Button asChild variant={key === "cloud" ? "brand" : "outline"} size="page" className="h-auto min-h-11 w-full whitespace-normal px-5 py-3 text-base">
                        <Link href={key === "cloud" ? ctaHref : "/docs/operations/deployment-profiles"}>
                            {key === "cloud" ? ctaLabel : t("pricing.explore")}
                            <ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" />
                        </Link>
                    </Button>
                </article>
            ))}
        </div>
    );
}
