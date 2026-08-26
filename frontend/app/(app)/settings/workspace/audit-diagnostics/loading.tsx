import DiagnosticsPanelSkeleton from "@/app/components/diagnostics/DiagnosticsPanelSkeleton";
import { Skeleton } from "@/components/ui/skeleton";

const PULSE_BARS = 14;
const TIMELINE_ROWS = 5;
const STAT_COUNT = 4;

/**
 * First-load stand-in for workspace Audit & diagnostics: the page heading, the audit log's stat
 * cluster, activity pulse, filter bar and timeline, then the diagnostics panel's own shared
 * skeleton — the same one its standalone route draws, so the section keeps one shape in both homes.
 */
export default function AuditDiagnosticsLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <section className="flex flex-col gap-6">
                <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                    <div className="space-y-1.5">
                        <Skeleton className="h-4 w-32" />
                        <Skeleton className="h-3.5 w-64 max-w-full" />
                    </div>
                    <div className="flex shrink-0 items-center gap-4 sm:gap-5">
                        {Array.from({ length: STAT_COUNT }, (_, stat) => (
                            <div key={stat} className="flex items-center gap-4 sm:gap-5">
                                {stat > 0 ? (
                                    <span aria-hidden className="h-8 w-px shrink-0 bg-border/50" />
                                ) : null}
                                <div className="space-y-1.5">
                                    <Skeleton className="h-4 w-10" />
                                    <Skeleton className="h-2.5 w-14" />
                                </div>
                            </div>
                        ))}
                    </div>
                </div>

                <div className="flex items-center gap-5 rounded-2xl border border-border bg-card p-4">
                    <div className="shrink-0 space-y-2">
                        <Skeleton className="h-2.5 w-20" />
                        <Skeleton className="h-7 w-12" />
                    </div>
                    <div className="ml-auto flex h-12 items-end gap-1.5">
                        {Array.from({ length: PULSE_BARS }, (_, bar) => (
                            <Skeleton
                                key={bar}
                                className="w-2 rounded-full"
                                style={{ height: `${20 + ((bar * 37) % 28)}px` }}
                            />
                        ))}
                    </div>
                </div>

                <div className="flex flex-wrap items-center gap-2 py-2.5">
                    <div className="flex flex-wrap items-center gap-1.5">
                        {Array.from({ length: 5 }, (_, filter) => (
                            <Skeleton key={filter} className="h-9 w-24 rounded-full" />
                        ))}
                    </div>
                    <div className="ml-auto w-full max-w-xl">
                        <Skeleton className="h-9 w-full rounded-full" />
                    </div>
                </div>

                <ul className="space-y-0">
                    {Array.from({ length: TIMELINE_ROWS }, (_, row) => (
                        <li key={row} className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3">
                            <div className="flex flex-col items-center pt-0.5">
                                <Skeleton className="size-8 rounded-full ring-4 ring-background" />
                            </div>
                            <div className="min-w-0 pb-4">
                                <div className="flex items-start gap-3 px-3 py-2">
                                    <div className="min-w-0 flex-1 space-y-1.5">
                                        <Skeleton className="h-4 w-3/4" />
                                        <Skeleton className="h-3 w-1/2" />
                                    </div>
                                    <div className="flex shrink-0 items-center gap-2 pt-0.5">
                                        <Skeleton className="h-3.5 w-16" />
                                        <Skeleton className="size-4 rounded-md" />
                                    </div>
                                </div>
                            </div>
                        </li>
                    ))}
                </ul>
            </section>

            <section className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3.5 w-72 max-w-full" />
                </div>
                <DiagnosticsPanelSkeleton scope="workspace" />
            </section>
        </div>
    );
}
