import Link from "next/link";
import { ArrowRightIcon, UserIcon, CloudIcon, BuildingOffice2Icon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import type { LandingTranslation } from "./sampleWorkspace";

const OPTIONS = [
    { key: "free", Icon: UserIcon },
    { key: "pro", Icon: CloudIcon },
    { key: "enterprise", Icon: BuildingOffice2Icon },
] as const;

/** Shows plan positioning and hosting options without prices or plan selection at signup. */
export default function LandingPricing({ t, ctaHref, ctaLabel }: { t: LandingTranslation; ctaHref: string; ctaLabel: string }) {
    return (
        <div className="mt-10 grid gap-5 lg:mt-12 lg:grid-cols-3 lg:gap-y-0">
            {OPTIONS.map(({ key, Icon }) => (
                <article key={key} className={`flex min-w-0 flex-col rounded-xl border p-6 sm:p-8 lg:row-span-4 lg:grid lg:grid-rows-subgrid ${key === "pro" ? "border-brand/40 bg-brand/5" : "border-border"}`}>
                    <Icon aria-hidden="true" className="size-8 text-brand-dark dark:text-brand" />
                    <h3 className="mt-6 text-3xl tracking-tight">{t(`pricing.${key}.name`)}</h3>
                    <p className="mb-8 mt-3 flex-1 text-base leading-relaxed text-muted-foreground">
                        {t(`pricing.${key}.body`)}
                        {key === "pro" && <span className="mt-3 block text-sm">{t("pricing.pro.hosting")}</span>}
                    </p>
                    <Button asChild variant={key === "pro" ? "brand" : "outline"} size="page" className="h-auto min-h-11 w-full whitespace-normal px-5 py-3 text-base">
                        <Link href={ctaHref}>
                            {ctaLabel}
                            <ArrowRightIcon aria-hidden="true" className="size-4 shrink-0" />
                        </Link>
                    </Button>
                </article>
            ))}
        </div>
    );
}
