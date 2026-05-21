import Link from "next/link";
import { formatCompactCurrency } from "@/app/lib/utils";
import { type Deal } from "@/app/lib/types";

export default function PipelineCard({ deals, render = "active" }: { deals: Deal[], render?: "active" | "inactive" | "previous"
 }   ) {
    let title = "";
    switch (render) {
        case "active":
            title = "Active Pipeline";
            break;
        case "inactive":
            title = "Closed Pipeline";
            break;
        case "previous":
            title = "Previous Pipeline";
            break;
        default:
            title = "Pipeline";
            break;
    }
    return (
        <>
            <div className="mt-6 mb-3 flex h-8 items-center">
                <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                    {/* // show all types and let the user click to switch between them */}
                    {/* <span className="cursor-pointer" onClick={() => setType(type === "active" ? "inactive" : type === "inactive" ? "previous" : "active")}>{title}</span> */}
                    {title}
                </h2>
            </div>
            <div className="overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                {deals.length === 0 ? (
                    <p className="px-6 py-6 text-sm text-neutral-500">No {render} deals.</p>
                ) : (
                    <ul className="divide-y divide-neutral-200">
                        {deals.map((deal) => (
                            <li key={deal.id}>
                                <Link
                                    href={`/records/deals/${deal.id}`}
                                    className="flex items-center justify-between px-6 py-4 transition-colors hover:bg-neutral-200/60"
                                >
                                    <span className="text-sm font-medium text-black">
                                        {deal.name}
                                    </span>
                                    <span className="text-sm text-neutral-500">
                                        {formatCompactCurrency(deal.value, deal.currency)}
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