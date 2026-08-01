import { Skeleton } from "@/components/ui/skeleton";

function ProviderSection() {
    return (
        <div className="space-y-4">
            <div className="space-y-1">
                <Skeleton className="h-3 w-28" />
                <Skeleton className="h-4 w-72 max-w-full" />
            </div>

            <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                <div className="flex items-center gap-4">
                    <div className="min-w-0 flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-40" />
                        <Skeleton className="h-3 w-64 max-w-full" />
                    </div>
                    <Skeleton className="h-5 w-8 shrink-0 rounded-full" />
                </div>

                <div className="space-y-3 pt-2">
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md" />
                </div>
            </div>
        </div>
    );
}

export default function DeliverySettingsLoading() {
    return (
        <div className="space-y-4">
            {Array.from({ length: 3 }).map((_, i) => (
                <ProviderSection key={i} />
            ))}
        </div>
    );
}
