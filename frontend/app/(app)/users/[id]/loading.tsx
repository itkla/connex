import { Skeleton } from '@/components/ui/skeleton';

export default function UserLoading() {
    return (
        <div className="mx-auto w-full max-w-5xl md:flex md:min-h-0 md:flex-1 md:flex-col">
            <Skeleton className="h-5 w-24" />

            <header className="mt-8 flex items-center gap-6 py-8">
                <Skeleton className="size-24 shrink-0 rounded-full" />
                <div className="flex flex-col gap-3">
                    <Skeleton className="h-10 w-64" />
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-6 w-28 rounded-md" />
                        <Skeleton className="h-4 w-48" />
                    </div>
                </div>
            </header>

            <div className="mt-4 grid grid-cols-1 gap-8 md:min-h-0 md:flex-1 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                <aside>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-16" />
                    </div>
                    <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 5 }).map((_, i) => (
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
                            <div className="p-2">
                                <Skeleton className="h-12 w-full rounded-xl" />
                            </div>
                        </div>
                    </div>
                </aside>

                <section className="md:flex md:min-h-0 md:flex-col">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-28" />
                    </div>

                    <div className="grid grid-cols-3 gap-3">
                        {Array.from({ length: 3 }).map((_, i) => (
                            <div
                                key={i}
                                className="flex flex-col rounded-2xl border border-border bg-card px-5 py-4"
                            >
                                <Skeleton className="h-3 w-16" />
                                <Skeleton className="mt-2 h-9 w-12" />
                            </div>
                        ))}
                    </div>

                    <div className="mt-6 mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-20" />
                    </div>
                    <div className="overflow-hidden rounded-2xl border border-border bg-card md:flex md:min-h-0 md:flex-1 md:flex-col">
                        <div className="divide-y divide-border">
                            {Array.from({ length: 6 }).map((_, i) => (
                                <div key={i} className="flex items-start gap-3 px-6 py-4">
                                    <Skeleton className="size-8 shrink-0 rounded-full" />
                                    <div className="flex min-w-0 flex-1 flex-col gap-2">
                                        <Skeleton className="h-4 w-3/4" />
                                        <Skeleton className="h-3 w-1/3" />
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                </section>
            </div>
        </div>
    );
}
