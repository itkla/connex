import { Skeleton } from "@/components/ui/skeleton";

function RoleRowsSkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                    <Skeleton className="size-9 shrink-0 rounded-lg" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-28" />
                        <Skeleton className="h-3 w-44" />
                    </div>
                </li>
            ))}
        </ul>
    );
}

export default function RolesSettingsLoading() {
    return (
        <div className="space-y-10">
            <section className="space-y-3">
                <div className="flex items-start justify-between gap-4">
                    <div className="space-y-1.5">
                        <Skeleton className="h-3 w-28" />
                        <Skeleton className="h-4 w-64" />
                    </div>
                    <Skeleton className="h-9 w-28 shrink-0 rounded-md" />
                </div>
                <RoleRowsSkeleton rows={3} />
            </section>

            <section className="space-y-3">
                <div className="flex items-baseline justify-between">
                    <Skeleton className="h-3 w-28" />
                </div>
                <RoleRowsSkeleton rows={2} />
            </section>
        </div>
    );
}
