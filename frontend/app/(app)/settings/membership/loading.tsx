import { Skeleton } from "@/components/ui/skeleton";

export default function MembershipLoading() {
    return (
        <div className="space-y-10">
            <section className="space-y-3">
                <Skeleton className="h-3 w-32" />
                <Skeleton className="h-4 w-72" />
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-3 px-4 py-3">
                        <Skeleton className="size-8 shrink-0 rounded-full" />
                        <Skeleton className="h-3.5 w-40" />
                    </div>
                </div>
            </section>

            <section className="space-y-3">
                <Skeleton className="h-3 w-28" />
                <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card px-4 py-4">
                    <Skeleton className="h-4 w-64" />
                    <Skeleton className="h-9 w-20 rounded-md" />
                </div>
            </section>
        </div>
    );
}
