import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
    return (
        <div className="min-h-screen bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <div className="flex items-start justify-between gap-4">
                    <div className="space-y-2">
                        <Skeleton className="h-10 w-40" />
                        <Skeleton className="h-4 w-72" />
                    </div>
                    <Skeleton className="h-9 w-28 rounded-md" />
                </div>

                <div className="flex items-center justify-between gap-3">
                    <Skeleton className="h-9 w-full max-w-xs rounded-full" />
                    <Skeleton className="h-8 w-36 rounded-full" />
                </div>

                <ul className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-3">
                    {Array.from({ length: 12 }).map((_, i) => (
                        <li key={i} className="overflow-hidden rounded-2xl border border-border bg-card">
                            <Skeleton className="h-24 w-full rounded-none" />
                            <div className="flex items-center justify-between px-3 py-2.5">
                                <Skeleton className="h-3 w-16" />
                                <Skeleton className="size-6 rounded-full" />
                            </div>
                        </li>
                    ))}
                </ul>
            </div>
        </div>
    );
}
