import { Skeleton } from "@/components/ui/skeleton";

export default function OrgAuditLoading() {
    return (
        <div className="space-y-4">
            <div>
                <div className="mb-3 flex h-8 items-center px-6">
                    <Skeleton className="h-3 w-24" />
                </div>
                <div className="px-6">
                    <Skeleton className="h-4 w-72 max-w-full" />
                </div>
            </div>

            <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {Array.from({ length: 3 }).map((_, i) => (
                    <li key={i} className="flex items-center justify-between gap-3 px-4 py-3">
                        <Skeleton className="h-4 w-48" />
                        <Skeleton className="h-3 w-16 shrink-0" />
                    </li>
                ))}
            </ul>
        </div>
    );
}
