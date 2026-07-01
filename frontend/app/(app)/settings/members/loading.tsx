import { Skeleton } from "@/components/ui/skeleton";

function MemberRows({ count }: { count: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-card ring-1 ring-border">
            {Array.from({ length: count }).map((_, i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3">
                    <Skeleton className="size-8 shrink-0 rounded-full" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-32" />
                        <Skeleton className="h-3 w-48" />
                    </div>
                    <Skeleton className="h-5 w-16 rounded-full" />
                </li>
            ))}
        </ul>
    );
}

export default function MembersLoading() {
    return (
        <div className="space-y-10">
            <section className="space-y-3">
                <div className="flex items-baseline justify-between">
                    <Skeleton className="h-3 w-20" />
                    <Skeleton className="h-3 w-16" />
                </div>
                <MemberRows count={4} />
            </section>

            <section className="space-y-4">
                <div className="space-y-1">
                    <Skeleton className="h-3 w-28" />
                    <Skeleton className="h-4 w-72" />
                </div>

                <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
                    <Skeleton className="h-9 w-full flex-1 rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md sm:w-36" />
                    <Skeleton className="h-9 w-full rounded-md sm:w-28" />
                </div>

                <div className="space-y-2 pt-2">
                    <Skeleton className="h-3 w-24" />
                    <MemberRows count={2} />
                </div>
            </section>
        </div>
    );
}
