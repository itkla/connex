import { Skeleton } from "@/components/ui/skeleton";

export default function OrgMembersLoading() {
    return (
        <div className="space-y-10">
            <section className="space-y-3">
                <div>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-32" />
                    </div>
                    <div className="px-6">
                        <Skeleton className="h-4 w-72 max-w-full" />
                    </div>
                </div>

                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: 2 }).map((_, i) => (
                        <li key={i} className="flex items-center gap-3 px-4 py-3">
                            <Skeleton className="size-9 shrink-0 rounded-full" />
                            <Skeleton className="h-4 w-40" />
                        </li>
                    ))}
                </ul>
            </section>

            <section className="space-y-4">
                <div>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-28" />
                    </div>
                    <div className="px-6">
                        <Skeleton className="h-4 w-64 max-w-full" />
                    </div>
                </div>

                <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
                    <Skeleton className="h-9 w-full flex-1 rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md sm:w-36" />
                    <Skeleton className="h-9 w-full rounded-md sm:w-36" />
                </div>
            </section>
        </div>
    );
}
