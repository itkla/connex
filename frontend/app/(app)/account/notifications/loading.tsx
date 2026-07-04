import { Skeleton } from "@/components/ui/skeleton";

export default function AccountNotificationsLoading() {
    return (
        <div className="space-y-3">
            <Skeleton className="h-3 w-32" />
            <Skeleton className="h-4 w-80 max-w-full" />

            <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {Array.from({ length: 4 }).map((_, i) => (
                    <li key={i} className="flex items-center gap-4 px-4 py-3.5">
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
    );
}
