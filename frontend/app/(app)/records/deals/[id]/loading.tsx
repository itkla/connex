import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

export default function DealDetailLoading() {
    return (
        <PageShell tier="wide">
                <header className="flex flex-col gap-6 py-4 sm:flex-row sm:items-end sm:justify-between">
                    <div className="flex flex-col gap-3">
                        <div className="flex flex-row flex-wrap items-center gap-3">
                            <Skeleton className="h-10 w-72" />
                            <Skeleton className="h-5 w-16 rounded-full" />
                        </div>
                        <div className="flex flex-wrap items-center gap-2">
                            <Skeleton className="h-7 w-36 rounded-md" />
                            <Skeleton className="h-4 w-40" />
                            <Skeleton className="h-4 w-28" />
                        </div>
                    </div>
                    <div className="flex flex-col items-end gap-1">
                        <Skeleton className="h-3 w-24" />
                        <Skeleton className="h-8 w-40" />
                    </div>
                </header>

                <div className="flex justify-start gap-2">
                    <Skeleton className="h-9 w-24 rounded-md" />
                    <Skeleton className="h-9 w-28 rounded-md" />
                    <Skeleton className="h-9 w-20 rounded-md" />
                    <Skeleton className="h-9 w-9 rounded-md" />
                </div>

                <div className="min-h-0" aria-hidden>
                    <Skeleton className="h-12 w-full rounded-xl" />
                </div>

                <div className="grid grid-cols-1 gap-8 xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)] xl:items-start">
                    <aside className="flex flex-col gap-6 xl:sticky xl:top-16 xl:max-h-[calc(100dvh-5rem)] xl:self-start xl:overflow-y-auto">
                        <div className="flex flex-col gap-6">
                            <div>
                                <div className="mb-3 flex h-8 items-center px-6">
                                    <Skeleton className="h-3 w-20" />
                                </div>
                                <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                    {Array.from({ length: 8 }).map((_, i) => (
                                        <div key={i} className="flex items-center justify-between px-6 py-3">
                                            <Skeleton className="h-3 w-24" />
                                            <Skeleton className="h-3 w-20" />
                                        </div>
                                    ))}
                                </div>
                            </div>
                            <div className="grid gap-2">
                                <Skeleton className="h-3 w-28" />
                                <Skeleton className="h-12 w-full rounded-lg" />
                                <Skeleton className="h-12 w-full rounded-lg" />
                            </div>
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-32" />
                            </div>
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                <ul className="divide-y divide-border">
                                    {Array.from({ length: 3 }).map((_, i) => (
                                        <li key={i} className="flex items-center gap-3 px-6 py-3 xl:px-4">
                                            <Skeleton className="size-12 shrink-0 rounded-full" />
                                            <div className="min-w-0 flex-1 space-y-2">
                                                <Skeleton className="h-3 w-28" />
                                                <Skeleton className="h-3 w-20" />
                                            </div>
                                        </li>
                                    ))}
                                </ul>
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
                            <div className="mb-3 flex h-8 items-center gap-1.5 px-6">
                                <Skeleton className="h-3 w-36" />
                            </div>
                            <Skeleton className="h-14 w-full sm:h-16" />
                        </div>
                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-28" />
                            </div>
                            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                                {Array.from({ length: 4 }).map((_, i) => (
                                    <div key={i} className="rounded-2xl border border-border bg-card p-4">
                                        <Skeleton className="h-3 w-20" />
                                        <Skeleton className="mt-2 h-7 w-24" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
                            <div className="rounded-2xl border border-border bg-card p-4">
                                <Skeleton className="h-32 w-full rounded-xl" />
                            </div>
                            <div className="rounded-2xl border border-border bg-card p-4">
                                <Skeleton className="h-32 w-full rounded-xl" />
                            </div>
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-28" />
                            </div>
                            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                                <div className="rounded-2xl border border-border bg-card p-3 md:col-span-2">
                                    <Skeleton className="h-3 w-32" />
                                    <Skeleton className="mt-3 h-28 w-full" />
                                </div>
                                <div className="rounded-2xl border border-border bg-card p-3">
                                    <Skeleton className="h-3 w-24" />
                                    <div className="mt-3 flex items-center gap-4">
                                        <Skeleton className="size-28 shrink-0 rounded-full" />
                                        <div className="flex-1 space-y-2">
                                            <Skeleton className="h-3 w-full" />
                                            <Skeleton className="h-3 w-4/5" />
                                            <Skeleton className="h-3 w-3/5" />
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <div className="rounded-2xl border border-border bg-card p-4">
                            <Skeleton className="h-16 w-full rounded-xl" />
                        </div>
                        <div className="rounded-2xl border border-border bg-card p-4">
                            <Skeleton className="h-16 w-full rounded-xl" />
                        </div>
                    </section>
                </div>

                <section className="grid grid-cols-1 gap-8 xl:grid-cols-2">
                    <div className="rounded-2xl border border-border bg-card p-4">
                        <Skeleton className="h-24 w-full rounded-xl" />
                    </div>
                    <div className="rounded-2xl border border-border bg-card p-4">
                        <Skeleton className="h-24 w-full rounded-xl" />
                    </div>
                </section>

                <section>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-20" />
                    </div>
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <div className="space-y-6 p-6">
                            {Array.from({ length: 5 }).map((_, i) => (
                                <div key={i} className="flex gap-3">
                                    <Skeleton className="size-8 shrink-0 rounded-full" />
                                    <div className="flex-1 space-y-2">
                                        <Skeleton className="h-3 w-32" />
                                        <Skeleton className="h-3 w-4/5" />
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                </section>
        </PageShell>
    );
}
