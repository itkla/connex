import { Skeleton } from '@/components/ui/skeleton';

/**
 * Stands in for the goals board: the eyebrow/title/subtitle header with its create action, then the
 * goals table — a titled card header over the column row and the goal rows beneath it.
 *
 * Both the create button and the trailing actions column are drawn because `GOAL_MANAGE` is the
 * common case; a reader without it sees one control settle away rather than the whole table shifting.
 */
export default function GoalsLoading() {
    return (
        <div className="min-h-full bg-background px-2 pb-12 pt-8 2xl:px-6">
            <div className="flex w-full flex-col gap-8">
                <header className="flex flex-wrap items-end justify-between gap-5">
                    <div className="space-y-3">
                        <Skeleton className="h-3 w-28" />
                        <Skeleton className="h-10 w-64" />
                        <Skeleton className="h-4 w-full max-w-xl" />
                    </div>
                    <Skeleton className="h-9 w-32 rounded-full" />
                </header>

                <section className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="space-y-2 border-b border-border px-5 py-4">
                        <Skeleton className="h-4 w-32" />
                        <Skeleton className="h-3.5 w-24" />
                    </div>

                    <div className="flex items-center gap-5 border-b border-border bg-muted/30 px-5 py-3">
                        {['w-16', 'w-14', 'w-20', 'w-16', 'w-12', 'w-14'].map((width) => (
                            <Skeleton key={width} className={`h-3 ${width}`} />
                        ))}
                        <Skeleton className="ml-auto h-3 w-16" />
                    </div>

                    {Array.from({ length: 5 }, (_, row) => (
                        <div
                            key={row}
                            className="flex items-center gap-5 border-b border-border px-5 py-4 last:border-b-0"
                        >
                            <Skeleton className="h-4 w-32" />
                            <Skeleton className="h-3.5 w-20" />
                            <Skeleton className="h-3.5 w-28" />
                            <Skeleton className="h-3.5 w-24" />
                            <Skeleton className="h-4 w-20" />
                            <Skeleton className="h-3.5 w-10" />
                            <div className="ml-auto flex items-center gap-1">
                                <Skeleton className="size-8 rounded-full" />
                                <Skeleton className="size-8 rounded-full" />
                            </div>
                        </div>
                    ))}
                </section>
            </div>
        </div>
    );
}
