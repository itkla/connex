import { Skeleton } from "@/components/ui/skeleton";

export default function SsoSettingsLoading() {
    return (
        <div className="space-y-3">
            <Skeleton className="h-3 w-32" />
            <Skeleton className="h-4 w-80 max-w-full" />

            <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-2/3 rounded-md" />
            </div>
        </div>
    );
}
