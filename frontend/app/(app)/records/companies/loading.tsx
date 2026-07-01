import { Skeleton } from "@/components/ui/skeleton";

export default function CompaniesLoading() {
    return (
        <div className="page-grid gap-y-6">
            <div className="flex items-center justify-between">
                <Skeleton className="h-10 w-52" />
                <div className="flex items-center gap-2">
                    <Skeleton className="h-9 w-28 rounded-md" />
                    <Skeleton className="h-9 w-24 rounded-md" />
                </div>
            </div>

            <div className="flex items-center gap-1 overflow-x-auto">
                <Skeleton className="h-8 w-14 rounded-md" />
                <Skeleton className="h-8 w-24 rounded-md" />
                <Skeleton className="h-8 w-20 rounded-md" />
            </div>

            <div className="rounded-2xl py-2.5">
                <div className="flex flex-wrap items-center gap-2">
                    <div className="ml-auto flex flex-wrap items-center gap-2">
                        <Skeleton className="h-9 w-28 rounded-full" />
                        <Skeleton className="h-9 w-24 rounded-full" />
                        <Skeleton className="h-9 w-20 rounded-full" />
                        <Skeleton className="h-9 w-64 max-w-full rounded-full" />
                    </div>
                </div>
            </div>

            <div className="grid grid-cols-1 gap-3">
                {Array.from({ length: 8 }).map((_, i) => (
                    <div
                        key={i}
                        className="flex items-center gap-4 rounded-2xl border border-border bg-card p-4"
                    >
                        <Skeleton className="size-16 shrink-0 rounded-2xl" />
                        <div className="min-w-0 flex-1 space-y-2">
                            <Skeleton className="h-5 w-48 max-w-full" />
                            <Skeleton className="h-4 w-32 max-w-full" />
                        </div>
                        <Skeleton className="size-10 shrink-0 rounded-md" />
                    </div>
                ))}
            </div>
        </div>
    );
}
