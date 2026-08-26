import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for one recipe: the badge/title/description header, the disclosure and
 * configure form in the main column, and the preview rail beside it. Shared by the route's
 * `loading.tsx` and the detail's own pre-fetch state so the page keeps one shape throughout
 * instead of trading one set of bones for another.
 */
export default function WorkflowRecipeDetailSkeleton() {
    return (
        <div className="space-y-8" aria-busy="true">
            <header className="space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                    <Skeleton className="h-5 w-20 rounded-full" />
                    <Skeleton className="h-5 w-28 rounded-full" />
                </div>
                <Skeleton className="h-9 w-80 max-w-full" />
                <Skeleton className="h-4 w-full max-w-2xl" />
            </header>

            <div className="grid gap-8 xl:grid-cols-[minmax(0,1.25fr)_minmax(20rem,0.75fr)]">
                <main className="space-y-6">
                    <div className="space-y-3 rounded-2xl border border-border bg-card p-5">
                        <Skeleton className="h-4 w-40" />
                        <Skeleton className="h-3.5 w-full max-w-md" />
                        <Skeleton className="h-3.5 w-3/4" />
                    </div>

                    <section className="space-y-4 rounded-2xl border border-border bg-card p-5">
                        <div className="space-y-2">
                            <Skeleton className="h-4 w-32" />
                            <Skeleton className="h-3.5 w-72 max-w-full" />
                        </div>
                        {Array.from({ length: 3 }, (_, field) => (
                            <div key={field} className="space-y-2">
                                <Skeleton className="h-3.5 w-28" />
                                <Skeleton className="h-9 w-full rounded-md" />
                            </div>
                        ))}
                    </section>
                </main>

                <aside className="space-y-4">
                    <div className="space-y-2 rounded-2xl border border-border bg-card p-4">
                        <Skeleton className="h-3.5 w-40" />
                        <Skeleton className="h-3 w-full" />
                        <Skeleton className="h-3 w-4/5" />
                    </div>
                    <Skeleton className="h-9 w-full rounded-full" />
                </aside>
            </div>
        </div>
    );
}
