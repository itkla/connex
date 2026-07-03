import { Skeleton } from "@/components/ui/skeleton";

export default function SecuritySettingsLoading() {
    return (
        <div className="space-y-3">
            <Skeleton className="h-3 w-32" />
            <Skeleton className="h-4 w-80 max-w-full" />

            <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {Array.from({ length: 2 }).map((_, i) => (
                    <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                        <Skeleton className="size-9 shrink-0 rounded-lg" />
                        <div className="min-w-0 flex-1 space-y-2">
                            <Skeleton className="h-3.5 w-32" />
                            <Skeleton className="h-3 w-48 max-w-full" />
                        </div>
                    </li>
                ))}
            </ul>
        </div>
    );
}
