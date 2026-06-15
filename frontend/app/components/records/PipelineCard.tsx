import Link from "next/link";
import { getLocale, getTranslations } from "next-intl/server";
import { formatCompactCurrency } from "@/app/lib/utils";
import { type Deal } from "@/app/lib/types";

export default async function PipelineCard({ deals, render = "active" }: { deals: Deal[], render?: "active" | "inactive" | "previous"
 }   ) {
    const t = await getTranslations('RecordsPipelineCard');
    const locale = await getLocale();
    let title = "";
    let emptyMessage = "";
    switch (render) {
        case "active":
            title = t('titleActive');
            emptyMessage = t('emptyActive');
            break;
        case "inactive":
            title = t('titleInactive');
            emptyMessage = t('emptyInactive');
            break;
        case "previous":
            title = t('titlePrevious');
            emptyMessage = t('emptyPrevious');
            break;
        default:
            title = t('titleDefault');
            emptyMessage = t('emptyDefault');
            break;
    }
    return (
        <>
            <div className="mt-6 mb-3 flex h-8 items-center">
                <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                    {/* // show all types and let the user click to switch between them */}
                    {/* <span className="cursor-pointer" onClick={() => setType(type === "active" ? "inactive" : type === "inactive" ? "previous" : "active")}>{title}</span> */}
                    {title}
                </h2>
            </div>
            <div className="overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                {deals.length === 0 ? (
                    <p className="px-6 py-6 text-sm text-muted-foreground">{emptyMessage}</p>
                ) : (
                    <ul className="divide-y divide-border">
                        {deals.map((deal) => (
                            <li key={deal.id}>
                                <Link
                                    href={`/records/deals/${deal.id}`}
                                    className="flex items-center justify-between px-6 py-4 transition-colors hover:bg-muted/60"
                                >
                                    <span className="text-sm font-medium text-foreground">
                                        {deal.name}
                                    </span>
                                    <span className="text-sm text-muted-foreground">
                                        {formatCompactCurrency(deal.value, deal.currency, locale)}
                                    </span>
                                </Link>
                            </li>
                        ))}
                    </ul>
                )}
            </div>
        </>
    );
}   