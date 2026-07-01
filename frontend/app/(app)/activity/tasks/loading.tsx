import { Skeleton } from '@/components/ui/skeleton';

export default function TasksLoading() {
    return (
        <div className="mx-auto w-full max-w-7xl space-y-6">
            <header className="flex flex-wrap items-start justify-between gap-4">
                <div className="space-y-2">
                    <Skeleton className="h-10 w-40" />
                    <Skeleton className="h-4 w-72 max-w-full" />
                </div>
                <div className="flex items-center gap-2">
                    <Skeleton className="h-9 w-[72px] rounded-full" />
                    <Skeleton className="h-9 w-28 rounded-md" />
                </div>
            </header>

            <div className="grid grid-cols-3 gap-4 rounded-2xl border border-border bg-card p-4">
                <div className="flex min-h-20 items-center gap-3.5">
                    <Skeleton className="size-12 shrink-0 rounded-full" />
                    <div className="min-w-0 space-y-2">
                        <Skeleton className="h-4 w-16" />
                        <Skeleton className="h-3 w-24" />
                    </div>
                </div>
                <div className="flex min-h-20 flex-col justify-center gap-2">
                    <Skeleton className="h-7 w-10" />
                    <Skeleton className="h-3 w-16" />
                </div>
                <div className="flex min-h-20 flex-col justify-center gap-2">
                    <Skeleton className="h-7 w-10" />
                    <Skeleton className="h-3 w-20" />
                </div>
            </div>

            <div className="grid grid-cols-1 gap-6 md:grid-cols-[200px_minmax(0,1fr)] md:gap-10">
                <aside className="space-y-3">
                    <Skeleton className="mx-3 h-3 w-16" />
                    <div className="space-y-2">
                        {Array.from({ length: 5 }).map((_, i) => (
                            <div key={i} className="flex items-center justify-between px-3 py-1">
                                <div className="flex items-center gap-2.5">
                                    <Skeleton className="size-4 rounded" />
                                    <Skeleton className="h-4 w-24" />
                                </div>
                                <Skeleton className="h-3 w-5" />
                            </div>
                        ))}
                        <div className="mx-3 my-3 h-px bg-border" />
                        <div className="flex items-center justify-between px-3 py-1">
                            <div className="flex items-center gap-2.5">
                                <Skeleton className="size-4 rounded" />
                                <Skeleton className="h-4 w-24" />
                            </div>
                            <Skeleton className="h-3 w-5" />
                        </div>
                    </div>
                </aside>

                <div className="min-w-0 space-y-4">
                    <div className="flex flex-wrap items-center gap-3">
                        <Skeleton className="h-9 w-24 rounded-full" />
                        <Skeleton className="h-9 w-24 rounded-full" />
                        <Skeleton className="ml-auto h-9 w-full max-w-sm rounded-full" />
                    </div>

                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 2 }).map((_, section) => (
                            <div key={section} className={section > 0 ? 'border-t border-border' : undefined}>
                                <div className="flex items-baseline justify-between px-5 pt-4 pb-2">
                                    <Skeleton className="h-4 w-24" />
                                    <Skeleton className="h-3 w-5" />
                                </div>
                                <div className="divide-y divide-border">
                                    {Array.from({ length: 3 }).map((_, row) => (
                                        <div key={row} className="flex items-center gap-3 px-5 py-3">
                                            <Skeleton className="size-[18px] shrink-0 rounded-full" />
                                            <Skeleton className="h-4 min-w-0 flex-1" />
                                            <Skeleton className="hidden h-5 w-28 rounded-full sm:block" />
                                            <Skeleton className="h-5 w-14 rounded-full" />
                                            <Skeleton className="size-6 shrink-0 rounded-full" />
                                        </div>
                                    ))}
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
}
