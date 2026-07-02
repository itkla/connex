import { Skeleton } from '@/components/ui/skeleton';

export default function MeLoading() {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-10">
                <header className="flex items-center gap-6">
                    <Skeleton className="h-24 w-24 shrink-0 rounded-full" />
                    <div className="flex flex-col gap-2">
                        <Skeleton className="h-7 w-40" />
                        <Skeleton className="h-10 w-64" />
                    </div>
                </header>

                <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                    <aside>
                        <div className="mb-3 flex h-8 items-center justify-between px-6">
                            <Skeleton className="h-3 w-20" />
                            <Skeleton className="h-8 w-8 rounded-md" />
                        </div>
                        <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                            {Array.from({ length: 4 }).map((_, i) => (
                                <div key={i} className="flex flex-col gap-2 px-6 py-4">
                                    <Skeleton className="h-4 w-24" />
                                    <Skeleton className="h-5 w-40" />
                                </div>
                            ))}
                        </div>

                        <div className="mt-6">
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-28" />
                            </div>
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                {Array.from({ length: 3 }).map((_, i) => (
                                    <div
                                        key={i}
                                        className="flex items-center gap-3 px-4 py-3"
                                    >
                                        <Skeleton className="size-5 shrink-0 rounded-md" />
                                        <Skeleton className="h-4 w-2/3" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div className="mt-6 px-6">
                            <Skeleton className="h-5 w-40" />
                        </div>
                    </aside>

                    <section>
                        <div className="mb-3 flex h-8 items-center px-6">
                            <Skeleton className="h-3 w-24" />
                        </div>

                        <div className="grid grid-cols-3 gap-3">
                            {Array.from({ length: 3 }).map((_, i) => (
                                <div
                                    key={i}
                                    className="flex flex-col rounded-2xl border border-border bg-card px-5 py-4"
                                >
                                    <Skeleton className="h-3 w-16" />
                                    <Skeleton className="mt-2 h-10 w-12" />
                                    <Skeleton className="mt-1 h-3 w-20" />
                                </div>
                            ))}
                        </div>

                        <div className="mt-6 overflow-hidden rounded-2xl border border-border bg-card">
                            <div className="divide-y divide-border">
                                {Array.from({ length: 6 }).map((_, i) => (
                                    <div key={i} className="flex items-start gap-4 px-6 py-5">
                                        <Skeleton className="size-9 shrink-0 rounded-full" />
                                        <div className="flex min-w-0 flex-1 flex-col gap-2">
                                            <Skeleton className="h-4 w-1/2" />
                                            <Skeleton className="h-3 w-3/4" />
                                        </div>
                                        <Skeleton className="h-3 w-16 shrink-0" />
                                    </div>
                                ))}
                            </div>
                        </div>
                    </section>
                </div>
            </div>
        </div>
    );
}
