import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

export default function ContactPageLoading() {
    return (
        <PageShell tier="wide">
                <header className="flex flex-col gap-6 py-4 sm:flex-row sm:items-end sm:justify-between">
                    <div className="flex items-center gap-6">
                        <Skeleton className="size-24 shrink-0 rounded-full" />
                        <div className="flex flex-col gap-3">
                            <div className="flex flex-row flex-wrap items-center gap-3">
                                <Skeleton className="h-10 w-56" />
                                <Skeleton className="h-6 w-16 rounded-full" />
                                <Skeleton className="h-6 w-16 rounded-full" />
                            </div>
                                <div className="flex flex-wrap items-center gap-2">
                                    <Skeleton className="h-7 w-28 rounded-md" />
                                    <Skeleton className="h-7 w-32 rounded-md" />
                                    <Skeleton className="h-6 w-16 rounded-full" />
                                </div>
                        </div>
                    </div>

                    <div className="flex flex-col items-end gap-2">
                        <Skeleton className="h-3 w-20" />
                        <div className="flex items-center">
                            {Array.from({ length: 3 }).map((_, i) => (
                                <Skeleton
                                    key={i}
                                    className="size-12 rounded-full ring-2 ring-background not-first:-ml-3"
                                />
                            ))}
                        </div>
                    </div>
                </header>

                <div className="flex justify-end gap-2">
                    <Skeleton className="h-9 w-24 rounded-md" />
                    <Skeleton className="h-9 w-28 rounded-md" />
                    <Skeleton className="h-9 w-9 rounded-md" />
                </div>

                <div className="min-h-0" aria-hidden>
                    <Skeleton className="h-12 w-full rounded-xl" />
                </div>

                <div className="grid grid-cols-1 gap-8 xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)] xl:items-start">
                    <aside className="flex flex-col gap-6">
                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-20" />
                            </div>
                            <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                {Array.from({ length: 7 }).map((_, i) => (
                                    <div key={i} className="flex items-center justify-between px-6 py-3">
                                        <Skeleton className="h-3 w-24" />
                                        <Skeleton className="h-3 w-28" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-28" />
                            </div>
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                {Array.from({ length: 2 }).map((_, i) => (
                                    <div key={i} className="space-y-2 border-b border-border px-6 py-4 last:border-b-0">
                                        <Skeleton className="h-3 w-28" />
                                        <Skeleton className="h-3 w-20" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-20" />
                            </div>
                            <div className="overflow-hidden rounded-2xl border border-border bg-card px-6 py-4">
                                <div className="flex items-center justify-between gap-4">
                                    <div className="min-w-0 flex-1 space-y-2">
                                        <Skeleton className="h-3 w-28" />
                                        <Skeleton className="h-3 w-full" />
                                    </div>
                                    <Skeleton className="h-5 w-9 rounded-full" />
                                </div>
                            </div>
                        </div>
                    </aside>

                    <section className="flex min-w-0 flex-col gap-8">
                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-28" />
                            </div>
                            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                                {Array.from({ length: 3 }).map((_, i) => (
                                    <div
                                        key={i}
                                        className="flex flex-col gap-4 rounded-2xl border border-border bg-card px-5 py-4"
                                    >
                                        <div className="flex items-center justify-between">
                                            <Skeleton className="h-3 w-16" />
                                            <Skeleton className="size-6 rounded-md" />
                                        </div>
                                        <Skeleton className="h-9 w-12" />
                                        <Skeleton className="h-3 w-20" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-32" />
                            </div>
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                {Array.from({ length: 2 }).map((_, i) => (
                                    <div
                                        key={i}
                                        className="flex items-center justify-between border-b border-border px-6 py-4 last:border-b-0"
                                    >
                                        <Skeleton className="h-4 w-40" />
                                        <Skeleton className="h-4 w-16" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-28" />
                            </div>
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                <div className="space-y-3 border-b border-border px-6 py-4">
                                    <Skeleton className="h-3 w-24" />
                                    <Skeleton className="h-4 w-3/4" />
                                </div>
                                {Array.from({ length: 3 }).map((_, i) => (
                                    <div
                                        key={i}
                                        className="flex items-center justify-between border-b border-border px-6 py-3 last:border-b-0"
                                    >
                                        <div className="space-y-2">
                                            <Skeleton className="h-3 w-28" />
                                            <Skeleton className="h-3 w-20" />
                                        </div>
                                        <Skeleton className="size-4 rounded-md" />
                                    </div>
                                ))}
                            </div>
                        </div>
                    </section>
                </div>

                <section>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <div className="overflow-hidden rounded-2xl border border-border bg-card px-6 py-8">
                        <Skeleton className="h-4 w-40" />
                    </div>
                </section>

                <section>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-20" />
                    </div>
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <div className="space-y-5 p-6">
                            {Array.from({ length: 5 }).map((_, i) => (
                                <div key={i} className="flex gap-4">
                                    <Skeleton className="size-8 shrink-0 rounded-full" />
                                    <div className="flex-1 space-y-2">
                                        <Skeleton className="h-4 w-1/2" />
                                        <Skeleton className="h-3 w-3/4" />
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                </section>
        </PageShell>
    );
}
