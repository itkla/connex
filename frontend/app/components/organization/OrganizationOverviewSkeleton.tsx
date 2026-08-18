import { Skeleton } from "@/components/ui/skeleton";

function SectionHeading() {
    return (
        <div className="space-y-1.5">
            <Skeleton className="h-4 w-44" />
            <Skeleton className="h-3.5 w-80 max-w-full" />
        </div>
    );
}

/**
 * First-load stand-in for the organization overview: the identity form, the workspace and authority
 * layout list, and the lifecycle panel — three settings sections, each a heading over a bordered
 * card. Shared by the route's `loading.tsx` and the panel's own pre-fetch state so the two never
 * disagree about the shape the page is about to take.
 */
export default function OrganizationOverviewSkeleton() {
    return (
        <div className="space-y-10" aria-busy="true">
            <section className="space-y-4">
                <SectionHeading />
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="grid gap-6 p-6 md:grid-cols-2">
                        {Array.from({ length: 2 }, (_, field) => (
                            <div key={field} className="space-y-2">
                                <Skeleton className="h-3.5 w-24" />
                                <Skeleton className="h-9 w-full rounded-md" />
                                <Skeleton className="h-3.5 w-56 max-w-full" />
                            </div>
                        ))}
                    </div>
                    <div className="flex items-center justify-end border-t border-border bg-muted/30 px-6 py-4">
                        <Skeleton className="h-9 w-28 rounded-full" />
                    </div>
                </div>
            </section>

            <section className="space-y-4">
                <SectionHeading />
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: 4 }, (_, row) => (
                        <li key={row} className="flex items-center gap-3 px-4 py-3.5">
                            <Skeleton className="size-9 shrink-0 rounded-xl" />
                            <div className="flex-1 space-y-2">
                                <Skeleton className="h-3.5 w-40" />
                                <Skeleton className="h-3 w-56 max-w-full" />
                            </div>
                            <Skeleton className="h-5 w-20 shrink-0 rounded-full" />
                        </li>
                    ))}
                </ul>
            </section>

            <section className="space-y-4">
                <SectionHeading />
                <div className="space-y-3 rounded-2xl border border-border bg-card p-5">
                    <Skeleton className="h-3.5 w-64 max-w-full" />
                    <div className="flex flex-wrap gap-2 pt-1">
                        <Skeleton className="h-9 w-36 rounded-full" />
                        <Skeleton className="h-9 w-32 rounded-full" />
                    </div>
                </div>
            </section>
        </div>
    );
}
