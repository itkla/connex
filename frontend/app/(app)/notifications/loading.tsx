import { Skeleton } from "@/components/ui/skeleton";

export default function NotificationsLoading() {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <div className="flex flex-wrap items-end justify-between gap-4 px-4 sm:px-6">
                    <div>
                        <Skeleton className="h-8 w-56" />
                        <Skeleton className="mt-2 h-4 w-72" />
                    </div>
                    <Skeleton className="h-9 w-36 rounded-md" />
                </div>

                <div className="px-4 sm:px-6">
                    <Skeleton className="h-9 w-72 rounded-full" />
                </div>

                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="divide-y divide-border">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <div key={i} className="flex gap-4 px-5 py-4">
                                <Skeleton className="size-9 shrink-0 rounded-full" />
                                <div className="flex-1 space-y-2 py-1">
                                    <Skeleton className="h-3.5 w-1/3" />
                                    <Skeleton className="h-3 w-3/4" />
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
}
