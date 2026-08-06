import { Skeleton } from "@/components/ui/skeleton";
import { PageShell } from "@/app/components/PageShell";

export default function CompanyDetailLoading() {
    return (
        <PageShell tier="wide">
                <header className="flex flex-col gap-6 py-4 sm:flex-row sm:items-end sm:justify-between">
                    <div className="flex items-center gap-6">
                        <Skeleton className="size-32 shrink-0 rounded-2xl" />
                        <div className="flex flex-col gap-3">
                            <div className="flex flex-row flex-wrap items-center gap-3">
                                <Skeleton className="h-10 w-56" />
                                <Skeleton className="h-7 w-20 rounded-full" />
                            </div>
                            <Skeleton className="h-7 w-24 rounded-md" />
                        </div>
                    </div>

                    <div className="flex flex-col items-end gap-2">
                        <Skeleton className="h-3 w-28" />
                        <div className="flex items-center -space-x-2">
                            {Array.from({ length: 3 }).map((_, i) => (
                                <Skeleton key={i} className="size-12 rounded-full ring-2 ring-background" />
                            ))}
                        </div>
                    </div>
                </header>

                <div className="flex justify-start">
                    <Skeleton className="size-9 rounded-md" />
                </div>

                <div className="grid grid-cols-1 gap-8 xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)] xl:items-start">
                    <aside className="flex flex-col gap-6">
                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-16" />
                            </div>
                            <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                {Array.from({ length: 7 }).map((_, i) => (
                                    <div key={i} className="flex flex-col gap-1 px-6 py-4">
                                        <Skeleton className="h-4 w-20" />
                                        <Skeleton className="h-5 w-40 max-w-full" />
                                    </div>
                                ))}
                            </dl>
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
                                        className="flex flex-col rounded-2xl border border-border bg-card px-5 py-4"
                                    >
                                        <Skeleton className="h-3 w-16" />
                                        <Skeleton className="mt-2 h-9 w-12" />
                                        <Skeleton className="mt-1 h-3 w-20" />
                                    </div>
                                ))}
                            </div>
                            <div className="mt-6 space-y-2">
                                {Array.from({ length: 2 }).map((_, i) => (
                                    <div key={i} className="rounded-xl border border-border p-3">
                                        <Skeleton className="h-3 w-24" />
                                        <Skeleton className="mt-2 h-6 w-20" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div className="overflow-hidden rounded-2xl border border-border bg-card px-5 py-5">
                            <div className="flex items-center justify-between gap-4">
                                <Skeleton className="h-3 w-36" />
                                <Skeleton className="h-6 w-16 rounded-full" />
                            </div>
                            <Skeleton className="mt-4 h-4 w-2/3" />
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center px-6">
                                <Skeleton className="h-3 w-28" />
                            </div>
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                {Array.from({ length: 3 }).map((_, i) => (
                                    <div
                                        key={i}
                                        className="flex items-center justify-between border-b border-border px-6 py-4 last:border-b-0"
                                    >
                                        <Skeleton className="h-4 w-40 max-w-full" />
                                        <Skeleton className="h-4 w-16" />
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div>
                            <div className="mb-3 flex h-8 items-center justify-between px-6">
                                <Skeleton className="h-3 w-20" />
                                <Skeleton className="size-4 rounded-md" />
                            </div>
                            <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                                {Array.from({ length: 3 }).map((_, i) => (
                                    <li
                                        key={i}
                                        className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-4"
                                    >
                                        <Skeleton className="size-12 rounded-full" />
                                        <div className="space-y-2">
                                            <Skeleton className="h-4 w-24 max-w-full" />
                                            <Skeleton className="h-3 w-20 max-w-full" />
                                        </div>
                                    </li>
                                ))}
                            </ul>
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

                <section className="flex flex-col gap-8">
                    <div className="rounded-xl border border-border p-3">
                        <Skeleton className="h-3 w-32" />
                        <Skeleton className="mt-3 h-28 w-full rounded-lg" />
                    </div>
                    <div>
                        <div className="mb-3 flex h-8 items-center px-6">
                            <Skeleton className="h-3 w-20" />
                        </div>
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <div className="space-y-6 p-6">
                                {Array.from({ length: 4 }).map((_, i) => (
                                    <div key={i} className="flex items-start gap-3">
                                        <Skeleton className="size-8 shrink-0 rounded-full" />
                                        <div className="flex-1 space-y-2">
                                            <Skeleton className="h-4 w-48 max-w-full" />
                                            <Skeleton className="h-3 w-32 max-w-full" />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </section>
        </PageShell>
    );
}
