import { Skeleton } from '@/components/ui/skeleton';

function PartyRow() {
    return (
        <div className="flex items-center gap-3">
            <Skeleton className="size-8 shrink-0 rounded-full" />
            <div className="min-w-0 flex-1 space-y-1.5">
                <Skeleton className="h-4 w-32" />
                <Skeleton className="h-3 w-40" />
            </div>
            <Skeleton className="h-5 w-16 rounded-full" />
        </div>
    );
}

function SuggestionCardSkeleton() {
    return (
        <article className="flex flex-col rounded-2xl border border-border bg-card p-4 sm:p-5">
            <div className="flex flex-col gap-3">
                <PartyRow />
                <div className="flex items-center gap-3">
                    <Skeleton className="size-8 shrink-0 rounded-full" />
                    <span className="h-px flex-1 bg-border" />
                </div>
                <PartyRow />
            </div>
            <div className="mt-4 flex flex-wrap gap-1.5">
                <Skeleton className="h-6 w-28 rounded-full" />
                <Skeleton className="h-6 w-36 rounded-full" />
            </div>
            <div className="mt-4 flex items-center justify-end gap-2">
                <Skeleton className="h-8 w-20 rounded-md" />
                <Skeleton className="h-8 w-20 rounded-md" />
            </div>
        </article>
    );
}

function LineageRowSkeleton() {
    return (
        <li className="px-4 py-3 sm:px-6">
            <div className="flex items-center gap-3">
                <div className="flex min-w-0 flex-1 items-center gap-2">
                    <Skeleton className="size-8 shrink-0 rounded-full" />
                    <Skeleton className="h-4 w-28" />
                    <Skeleton className="size-4 shrink-0" />
                    <Skeleton className="size-8 shrink-0 rounded-full" />
                    <Skeleton className="h-4 w-28" />
                </div>
                <div className="shrink-0 space-y-1.5 text-right">
                    <Skeleton className="ml-auto h-3 w-16" />
                    <Skeleton className="ml-auto h-3 w-20" />
                </div>
            </div>
        </li>
    );
}

export default function IntroductionsLoading() {
    return (
        <div className="min-h-screen bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <header className="px-6">
                    <Skeleton className="h-8 w-56" />
                    <Skeleton className="mt-2 h-4 w-80" />
                </header>

                <div className="flex flex-col gap-10">
                    <section>
                        <div className="mb-3 flex h-8 items-center justify-between">
                            <Skeleton className="ml-6 h-3 w-40" />
                            <div className="px-1">
                                <Skeleton className="h-8 w-28 rounded-md" />
                            </div>
                        </div>
                        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                            {Array.from({ length: 4 }).map((_, i) => (
                                <SuggestionCardSkeleton key={i} />
                            ))}
                        </div>
                    </section>

                    <section>
                        <div className="mb-3 flex h-8 items-center">
                            <Skeleton className="ml-6 h-3 w-32" />
                        </div>
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <ul className="divide-y divide-border">
                                {Array.from({ length: 5 }).map((_, i) => (
                                    <LineageRowSkeleton key={i} />
                                ))}
                            </ul>
                        </div>
                    </section>
                </div>
            </div>
        </div>
    );
}
