import { Skeleton } from '@/components/ui/skeleton';

function SectionLabel() {
    return (
        <div className="mb-3 flex h-8 items-center px-6">
            <Skeleton className="h-3 w-24" />
        </div>
    );
}

export default function ContactPageLoading() {
    return (
        <div className="mx-auto w-full max-w-5xl md:flex md:min-h-0 md:flex-1 md:flex-col">
            <div className="flex flex-row justify-between">
                <Skeleton className="h-5 w-28" />
            </div>

            <header className="mt-8 flex flex-wrap items-center justify-between gap-6">
                <div className="flex items-center gap-6 py-8">
                    <Skeleton className="size-24 shrink-0 rounded-full" />
                    <div className="flex flex-col gap-2">
                        <div className="flex flex-row flex-wrap items-center gap-3">
                            <Skeleton className="h-9 w-56" />
                            <Skeleton className="h-6 w-16 rounded-full" />
                        </div>
                        <div className="flex flex-wrap items-center gap-2">
                            <Skeleton className="h-6 w-28 rounded-md" />
                            <Skeleton className="h-6 w-32 rounded-md" />
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

            <div className="mt-4 flex justify-end">
                <Skeleton className="h-9 w-32 rounded-lg" />
            </div>

            <div className="mt-12 grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)] md:min-h-0 md:flex-1">
                <aside>
                    <SectionLabel />
                    <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <div key={i} className="flex items-center justify-between gap-4 px-6 py-4">
                                <Skeleton className="h-4 w-20" />
                                <Skeleton className="h-4 w-32" />
                            </div>
                        ))}
                    </div>

                    <div className="mt-6">
                        <SectionLabel />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card px-6 py-5">
                            <div className="flex items-center justify-between gap-4">
                                <Skeleton className="h-4 w-28" />
                                <Skeleton className="h-4 w-16" />
                            </div>
                        </div>
                    </div>
                </aside>

                <section className="md:flex md:min-h-0 md:flex-col">
                    <SectionLabel />
                    <div className="grid grid-cols-3 gap-3">
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

                    <div className="mt-6">
                        <SectionLabel />
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

                    <div className="mt-6">
                        <SectionLabel />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            {Array.from({ length: 3 }).map((_, i) => (
                                <div
                                    key={i}
                                    className="flex items-center gap-4 border-b border-border px-6 py-4 last:border-b-0"
                                >
                                    <Skeleton className="size-10 shrink-0 rounded-full" />
                                    <div className="flex-1 space-y-2">
                                        <Skeleton className="h-4 w-1/3" />
                                        <Skeleton className="h-3 w-1/2" />
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>

                    <div className="mt-6">
                        <SectionLabel />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card md:flex md:min-h-0 md:flex-1 md:flex-col">
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
                    </div>
                </section>
            </div>
        </div>
    );
}
