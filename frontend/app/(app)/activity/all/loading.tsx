import { Skeleton } from "@/components/ui/skeleton";

export default function ActivityAllLoading() {
    return (
        <div className="min-h-screen bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <header className="flex flex-wrap items-start justify-between gap-4">
                    <div className="space-y-2">
                        <Skeleton className="h-10 w-40" />
                        <Skeleton className="h-4 w-64 max-w-full" />
                    </div>
                    <Skeleton className="h-9 w-32 rounded-md" />
                </header>

                <div className="flex items-center gap-5 rounded-2xl border border-border bg-card p-4">
                    <div className="shrink-0 space-y-2">
                        <Skeleton className="h-7 w-10" />
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <div className="ml-auto flex h-10 items-end gap-1.5">
                        {Array.from({ length: 7 }).map((_, i) => (
                            <Skeleton key={i} className="w-2 rounded-full" style={{ height: 8 + ((i * 5) % 32) }} />
                        ))}
                    </div>
                </div>

                <div className="grid grid-cols-1 gap-6 md:grid-cols-[200px_minmax(0,1fr)] md:gap-10">
                    <aside className="md:sticky md:top-6 md:self-start">
                        <Skeleton className="mb-2 ml-3 h-3 w-16" />
                        <nav className="space-y-0.5">
                            {Array.from({ length: 6 }).map((_, i) => (
                                <div key={i} className="flex items-center justify-between px-3 py-2">
                                    <div className="flex items-center gap-2.5">
                                        <Skeleton className="size-4 rounded" />
                                        <Skeleton className="h-4 w-16" />
                                    </div>
                                    <Skeleton className="h-3 w-5" />
                                </div>
                            ))}
                        </nav>
                    </aside>

                    <div className="min-w-0 space-y-5">
                        <div className="flex flex-wrap items-center gap-3">
                            <Skeleton className="h-9 w-full max-w-sm rounded-full" />
                            <Skeleton className="h-8 w-24 rounded-full" />
                            <Skeleton className="h-8 w-24 rounded-full" />
                        </div>

                        <ul className="relative space-y-6">
                            <li className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3">
                                <div className="flex justify-center pt-2">
                                    <Skeleton className="size-2 rounded-full" />
                                </div>
                                <div className="pt-1">
                                    <Skeleton className="h-4 w-40" />
                                </div>
                            </li>
                            {Array.from({ length: 5 }).map((_, i) => (
                                <li key={i} className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3">
                                    <div className="flex justify-center">
                                        <Skeleton className="mt-0.5 size-8 rounded-full" />
                                    </div>
                                    <div className="space-y-2 px-3 py-2">
                                        <div className="flex items-start justify-between gap-2">
                                            <div className="min-w-0 flex-1 space-y-2">
                                                <Skeleton className="h-4 w-3/4" />
                                                <Skeleton className="h-3 w-16" />
                                            </div>
                                            <Skeleton className="h-3 w-10" />
                                        </div>
                                        <div className="flex flex-wrap items-center gap-1.5">
                                            <Skeleton className="h-5 w-24 rounded-full" />
                                            <Skeleton className="h-5 w-20 rounded-full" />
                                            <Skeleton className="ml-auto size-6 rounded-full" />
                                        </div>
                                    </div>
                                </li>
                            ))}
                        </ul>
                    </div>
                </div>
            </div>
        </div>
    );
}
