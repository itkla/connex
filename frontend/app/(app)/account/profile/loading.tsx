import { Skeleton } from "@/components/ui/skeleton";

export default function AccountProfileLoading() {
    return (
        <div className="space-y-3">
            <Skeleton className="h-3 w-24" />
            <Skeleton className="h-4 w-80 max-w-full" />

            <div className="space-y-6 rounded-2xl border border-border bg-card p-6">
                <div className="flex items-center gap-4">
                    <Skeleton className="size-20 shrink-0 rounded-full" />
                    <div className="space-y-2">
                        <Skeleton className="h-4 w-28" />
                        <Skeleton className="h-3 w-44" />
                    </div>
                </div>
                {Array.from({ length: 4 }).map((_, i) => (
                    <div key={i} className="space-y-2">
                        <Skeleton className="h-3.5 w-24" />
                        <Skeleton className="h-9 w-full rounded-lg" />
                    </div>
                ))}
            </div>
        </div>
    );
}
