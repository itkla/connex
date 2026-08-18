import { Skeleton } from "@/components/ui/skeleton";

const SECTION_COUNT = 5;

/**
 * First-load stand-in for a diagnostics panel at either scope: the refresh control, then the five
 * sections — profile capabilities, provider readiness, secret store, job runs, mail deliverability —
 * each a heading over the rows {@link DiagnosticsSection} draws while its source resolves.
 */
export default function DiagnosticsPanelSkeleton() {
    return (
        <div className="space-y-10" aria-busy="true">
            <div className="flex items-center justify-end">
                <Skeleton className="h-8 w-24 rounded-full" />
            </div>

            {Array.from({ length: SECTION_COUNT }, (_, section) => (
                <section key={section} className="space-y-4">
                    <div className="space-y-1.5">
                        <Skeleton className="h-4 w-44" />
                        <Skeleton className="h-3.5 w-80 max-w-full" />
                    </div>
                    <div className="space-y-2">
                        <Skeleton className="h-9 w-full rounded-lg" />
                        <Skeleton className="h-9 w-4/5 rounded-lg" />
                        <Skeleton className="h-9 w-2/3 rounded-lg" />
                    </div>
                </section>
            ))}
        </div>
    );
}
