import { Skeleton } from '@/components/ui/skeleton';

function FilterGroup() {
    return (
        <div className="space-y-2">
            <Skeleton className="h-3.5 w-20" />
            {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="flex items-center gap-2">
                    <Skeleton className="size-4 rounded" />
                    <Skeleton className="h-3.5 w-32" />
                </div>
            ))}
        </div>
    );
}

export default function NewReportLoading() {
    return (
        <div className="min-h-full bg-background px-2 pb-12 pt-8">
            <div className="mx-auto w-full max-w-[100rem]">
                <header className="flex flex-wrap items-end justify-between gap-5 border-b border-border pb-6">
                    <div>
                        <Skeleton className="mb-2 h-3 w-28" />
                        <Skeleton className="h-10 w-72 max-w-full" />
                        <Skeleton className="mt-2 h-4 w-96 max-w-full" />
                    </div>
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-9 w-24 rounded-md" />
                        <Skeleton className="h-9 w-28 rounded-md" />
                    </div>
                </header>

                <div className="mt-8 grid grid-cols-1 gap-8 xl:grid-cols-[22rem_minmax(0,1fr)]">
                    <aside className="space-y-6">
                        <section className="rounded-2xl border border-border bg-card p-5">
                            <Skeleton className="h-4 w-28" />
                            <div className="mt-5 space-y-4">
                                <div className="space-y-2">
                                    <Skeleton className="h-3.5 w-16" />
                                    <Skeleton className="h-9 w-full rounded-lg" />
                                </div>
                                <div className="space-y-2">
                                    <Skeleton className="h-3.5 w-24" />
                                    <Skeleton className="h-20 w-full rounded-lg" />
                                </div>
                                {Array.from({ length: 2 }).map((_, i) => (
                                    <div key={i} className="space-y-2">
                                        <Skeleton className="h-3.5 w-20" />
                                        <Skeleton className="h-9 w-full rounded-lg" />
                                    </div>
                                ))}
                            </div>
                        </section>

                        <section className="rounded-2xl border border-border bg-card p-5">
                            <Skeleton className="h-4 w-24" />
                            <Skeleton className="mt-1 h-3 w-56 max-w-full" />
                            <div className="mt-5 space-y-5">
                                {Array.from({ length: 4 }).map((_, i) => (
                                    <FilterGroup key={i} />
                                ))}
                            </div>
                        </section>
                    </aside>

                    <main>
                        <div className="mb-5 flex items-end justify-between gap-4">
                            <div className="space-y-2">
                                <Skeleton className="h-6 w-40" />
                                <Skeleton className="h-4 w-72 max-w-full" />
                            </div>
                            <Skeleton className="h-9 w-32 rounded-md" />
                        </div>
                        <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
                            {Array.from({ length: 2 }).map((_, i) => (
                                <Skeleton key={i} className="h-72 rounded-2xl" />
                            ))}
                        </div>
                    </main>
                </div>
            </div>
        </div>
    );
}
