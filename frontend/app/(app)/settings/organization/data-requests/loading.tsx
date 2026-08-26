import { Skeleton } from "@/components/ui/skeleton";

const REQUEST_ROWS = 3;

/**
 * First-load stand-in for the organization's Data requests destination: the page heading, then the
 * request list under its own heading, whose filter and new-request controls sit beside it.
 */
export default function OrganizationDataRequestsLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <section className="space-y-4">
                <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                    <div className="space-y-1.5">
                        <Skeleton className="h-4 w-40" />
                        <Skeleton className="h-3.5 w-80 max-w-full" />
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                        <Skeleton className="h-8 w-28 rounded-md" />
                        <Skeleton className="h-8 w-32 rounded-md" />
                    </div>
                </div>
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: REQUEST_ROWS }, (_, row) => (
                        <li key={row} className="flex items-center justify-between gap-3 px-4 py-3">
                            <Skeleton className="h-4 w-56 max-w-full" />
                            <Skeleton className="h-4 w-20 shrink-0 rounded-4xl" />
                        </li>
                    ))}
                </ul>
            </section>
        </div>
    );
}
