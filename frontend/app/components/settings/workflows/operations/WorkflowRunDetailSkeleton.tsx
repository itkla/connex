import { Skeleton } from "@/components/ui/skeleton";

function SectionHeading() {
    return (
        <div className="space-y-2">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-3.5 w-72 max-w-full" />
        </div>
    );
}

function MetricCard({ rows }: { rows: number }) {
    return (
        <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
            <div className="flex items-center justify-between gap-3">
                <Skeleton className="h-3.5 w-24" />
                <Skeleton className="h-5 w-16 rounded-full" />
            </div>
            <div className="grid grid-cols-2 gap-3">
                {Array.from({ length: rows * 2 }, (_, cell) => (
                    <div key={cell} className="space-y-1.5">
                        <Skeleton className="h-3 w-16" />
                        <Skeleton className="h-4 w-10" />
                    </div>
                ))}
            </div>
        </section>
    );
}

/**
 * First-load stand-in for one workflow run: the run header, the step path and definition-change
 * lists in the main column, and the health, backlog, and controls cards in the rail. Shared by the
 * route's `loading.tsx` and the detail's own pre-fetch state so the page keeps one shape throughout.
 */
export default function WorkflowRunDetailSkeleton() {
    return (
        <div className="space-y-8" aria-busy="true">
            <div className="space-y-3">
                <Skeleton className="h-8 w-24 rounded-full" />
                <Skeleton className="h-9 w-72 max-w-full" />
                <Skeleton className="h-4 w-56" />
            </div>

            <div className="grid gap-6 xl:grid-cols-[minmax(0,1.5fr)_minmax(18rem,1fr)]">
                <main className="space-y-6">
                    <section className="space-y-3">
                        <SectionHeading />
                        <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                            {Array.from({ length: 4 }, (_, step) => (
                                <li key={step} className="flex items-center gap-3 px-4 py-3.5">
                                    <Skeleton className="size-8 shrink-0 rounded-full" />
                                    <div className="flex-1 space-y-2">
                                        <Skeleton className="h-3.5 w-44" />
                                        <Skeleton className="h-3 w-64 max-w-full" />
                                    </div>
                                    <Skeleton className="h-3 w-16 shrink-0" />
                                </li>
                            ))}
                        </ol>
                    </section>

                    <section className="space-y-3">
                        <SectionHeading />
                        <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                            {Array.from({ length: 3 }, (_, change) => (
                                <li key={change} className="space-y-2 px-4 py-3.5">
                                    <Skeleton className="h-3.5 w-52" />
                                    <Skeleton className="h-3 w-72 max-w-full" />
                                </li>
                            ))}
                        </ol>
                    </section>
                </main>

                <aside className="space-y-6">
                    <MetricCard rows={1} />
                    <MetricCard rows={2} />
                    <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
                        <Skeleton className="h-3.5 w-20" />
                        <div className="flex flex-wrap gap-2">
                            <Skeleton className="h-8 w-24 rounded-full" />
                            <Skeleton className="h-8 w-20 rounded-full" />
                            <Skeleton className="h-8 w-28 rounded-full" />
                        </div>
                    </section>
                </aside>
            </div>
        </div>
    );
}
