import { Skeleton } from "@/components/ui/skeleton";

export default function OrgDataRequestsLoading() {
    return (
        <div className="space-y-4">
            <div>
                <div className="mb-3 flex h-8 items-center justify-between gap-4">
                    <div className="px-6">
                        <Skeleton className="h-3 w-32" />
                    </div>
                    <div className="flex shrink-0 items-center gap-2 px-1">
                        <Skeleton className="h-8 w-28 rounded-md" />
                        <Skeleton className="h-8 w-32 rounded-md" />
                    </div>
                </div>
                <div className="px-6">
                    <Skeleton className="h-4 w-72 max-w-full" />
                </div>
            </div>

            <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {Array.from({ length: 3 }).map((_, i) => (
                    <li key={i} className="flex items-center justify-between gap-3 px-4 py-3">
                        <Skeleton className="h-4 w-56 max-w-full" />
                        <Skeleton className="h-4 w-20 shrink-0 rounded-4xl" />
                    </li>
                ))}
            </ul>
        </div>
    );
}
