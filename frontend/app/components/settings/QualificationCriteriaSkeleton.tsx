import { Skeleton } from "@/components/ui/skeleton";

const DIMENSION_COUNT = 2;
const ROWS_PER_DIMENSION = 3;

/**
 * First-load stand-in for the qualification criteria panel: one bordered card per scoring dimension,
 * each with its heading, blocking count, weight-share meter, and criterion rows. Shared by the
 * route's `loading.tsx` and the panel's own pre-fetch state so the page keeps one shape throughout.
 */
export default function QualificationCriteriaSkeleton() {
    return (
        <div className="space-y-6" aria-busy="true">
            {Array.from({ length: DIMENSION_COUNT }, (_, dimension) => (
                <section key={dimension} className="rounded-2xl border border-border bg-card">
                    <header className="space-y-3 px-6 py-4">
                        <div className="flex flex-wrap items-baseline justify-between gap-2">
                            <Skeleton className="h-3.5 w-28" />
                            <Skeleton className="h-3 w-36" />
                        </div>
                        <Skeleton className="h-1.5 w-full rounded-full" />
                    </header>

                    <ul className="divide-y divide-border border-t border-border">
                        {Array.from({ length: ROWS_PER_DIMENSION }, (_, row) => (
                            <li key={row} className="flex items-center justify-between gap-4 px-6 py-3">
                                <div className="flex min-w-0 items-start gap-2">
                                    <Skeleton className="mt-0.5 size-4 shrink-0 rounded" />
                                    <Skeleton className="h-4 w-48 max-w-full" />
                                </div>
                                <Skeleton className="h-3 w-16 shrink-0" />
                            </li>
                        ))}
                    </ul>
                </section>
            ))}
        </div>
    );
}
