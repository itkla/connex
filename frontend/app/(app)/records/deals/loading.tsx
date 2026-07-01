import { Skeleton } from '@/components/ui/skeleton';

export default function DealsLoading() {
    return (
        <div className="page-grid gap-y-6">
            <div className="flex items-center justify-between">
                <Skeleton className="h-10 w-40" />
                <div className="flex items-center gap-2">
                    <Skeleton className="h-9 w-24 rounded-full" />
                    <Skeleton className="h-9 w-28 rounded-md" />
                </div>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <div className="rounded-2xl border border-border bg-card p-4 sm:col-span-2">
                    <Skeleton className="h-3 w-24" />
                    <Skeleton className="mt-3 h-40 w-full" />
                </div>
                <div className="rounded-2xl border border-border bg-card p-4">
                    <Skeleton className="h-3 w-20" />
                    <Skeleton className="mt-3 h-40 w-full" />
                </div>
            </div>

            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
                {Array.from({ length: 5 }).map((_, i) => (
                    <div key={i} className="rounded-2xl border border-border bg-card p-4">
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="mt-2 h-7 w-16" />
                    </div>
                ))}
            </div>

            <div className="flex items-center gap-2">
                {Array.from({ length: 4 }).map((_, i) => (
                    <Skeleton key={i} className="h-8 w-24 rounded-full" />
                ))}
                <Skeleton className="ml-auto size-8 rounded-full" />
            </div>

            <div className="flex flex-wrap items-center gap-3">
                <Skeleton className="h-9 w-full max-w-sm rounded-full" />
                <Skeleton className="ml-auto h-9 w-28 rounded-full" />
            </div>

            <div className="grid grid-cols-1 gap-3">
                {Array.from({ length: 6 }).map((_, i) => (
                    <div
                        key={i}
                        className="flex items-center gap-4 rounded-2xl border border-border bg-card p-4"
                    >
                        <Skeleton className="size-16 shrink-0 rounded-2xl" />
                        <div className="min-w-0 flex-1 space-y-2">
                            <div className="flex items-center gap-2">
                                <Skeleton className="h-5 w-40" />
                                <Skeleton className="h-4 w-14 rounded-full" />
                            </div>
                            <div className="flex flex-wrap items-center gap-3">
                                <Skeleton className="h-3 w-28" />
                                <Skeleton className="h-3 w-24" />
                                <Skeleton className="h-3 w-20" />
                            </div>
                        </div>
                        <div className="space-y-1.5 text-right">
                            <Skeleton className="ml-auto h-5 w-20" />
                            <Skeleton className="ml-auto h-3 w-10" />
                        </div>
                        <Skeleton className="size-8 shrink-0 rounded-full" />
                        <Skeleton className="size-8 shrink-0 rounded-md" />
                    </div>
                ))}
            </div>
        </div>
    );
}
