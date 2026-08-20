import { Skeleton } from "@/components/ui/skeleton";

function RowsSkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, index) => (
                <li key={index} className="flex items-center gap-3 px-4 py-3">
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

function SectionSkeleton({ rows }: { rows: number }) {
    return (
        <section className="space-y-4">
            <div className="space-y-1">
                <Skeleton className="h-5 w-40" />
                <Skeleton className="h-4 w-72 max-w-full" />
            </div>
            <RowsSkeleton rows={rows} />
        </section>
    );
}

export default function PeopleAccessLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>
            <SectionSkeleton rows={3} />
            <SectionSkeleton rows={2} />
            <SectionSkeleton rows={2} />
            <SectionSkeleton rows={4} />
        </div>
    );
}
