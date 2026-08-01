import { Skeleton } from "@/components/ui/skeleton";
import { PageShell } from "@/app/components/PageShell";

export default function CampaignsLoading() {
    return (
        <PageShell tier="wide">
                <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
                    <div className="space-y-2">
                        <Skeleton className="h-10 w-40" />
                        <Skeleton className="h-4 w-80 max-w-full" />
                    </div>
                    <Skeleton className="h-9 w-36 rounded-md" />
                </div>

                <div className="flex flex-col gap-3">
                    <Skeleton className="h-3 w-24" />
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <li key={i} className="flex items-center gap-3 px-4 py-3.5 sm:px-5">
                                <div className="min-w-0 flex-1 space-y-2">
                                    <Skeleton className="h-4 w-48 max-w-full" />
                                    <Skeleton className="h-3 w-64 max-w-full" />
                                </div>
                                <Skeleton className="h-5 w-16 rounded-full" />
                                <Skeleton className="size-4 rounded" />
                            </li>
                        ))}
                    </ul>
                </div>
        </PageShell>
    );
}
