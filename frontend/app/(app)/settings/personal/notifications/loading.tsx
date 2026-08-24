import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for Notification preferences: the page heading, then the two sections it
 * composes — the per-type delivery grid, and the quiet-hours block under it.
 */
export default function PersonalNotificationsLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-72" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <div className="flex flex-col gap-10">
                <div className="space-y-3">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-28" />
                    </div>
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 4 }, (_, row) => (
                            <li key={row} className="flex items-center gap-4 px-4 py-3.5">
                                <Skeleton className="size-5 shrink-0 rounded-md" />
                                <div className="min-w-0 flex-1 space-y-2">
                                    <Skeleton className="h-3.5 w-40" />
                                    <Skeleton className="h-3.5 w-64 max-w-full" />
                                </div>
                                <Skeleton className="h-[18.4px] w-8 shrink-0 rounded-full" />
                            </li>
                        ))}
                    </ul>
                </div>

                <div className="space-y-3">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <div className="flex items-center gap-4 px-4 py-3.5">
                            <Skeleton className="size-5 shrink-0 rounded-md" />
                            <div className="min-w-0 flex-1 space-y-2">
                                <Skeleton className="h-3.5 w-32" />
                                <Skeleton className="h-3.5 w-56 max-w-full" />
                            </div>
                            <Skeleton className="h-[18.4px] w-16 shrink-0 rounded-full" />
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
