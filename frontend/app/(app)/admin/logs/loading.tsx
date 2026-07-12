import { Skeleton } from "@/components/ui/skeleton";

const STAT_COUNT = 4;
const FILTER_COUNT = 5;
const PULSE_BARS = 14;
const TIMELINE_ROWS = 6;

export default function AuditLogLoading() {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <header className="flex flex-wrap items-end justify-between gap-4">
                    <div className="space-y-2">
                        <Skeleton className="h-9 w-56" />
                        <Skeleton className="h-4 w-72" />
                    </div>
                    <div className="flex items-center gap-4 sm:gap-5">
                        {Array.from({ length: STAT_COUNT }).map((_, i) => (
                            <div key={i} className="flex items-center gap-4 sm:gap-5">
                                {i > 0 && <span aria-hidden className="h-8 w-px shrink-0 bg-border/50" />}
                                <div className="space-y-1.5">
                                    <Skeleton className="h-4 w-10" />
                                    <Skeleton className="h-2.5 w-14" />
                                </div>
                            </div>
                        ))}
                    </div>
                </header>

                <div className="flex items-center gap-5 rounded-2xl border border-border bg-card p-4">
                    <div className="shrink-0 space-y-2">
                        <Skeleton className="h-2.5 w-20" />
                        <Skeleton className="h-7 w-12" />
                    </div>
                    <div className="ml-auto flex h-12 items-end gap-1.5">
                        {Array.from({ length: PULSE_BARS }).map((_, i) => (
                            <Skeleton
                                key={i}
                                className="w-2 rounded-full"
                                style={{ height: `${20 + ((i * 37) % 28)}px` }}
                            />
                        ))}
                    </div>
                </div>

                <div className="flex flex-col gap-6">
                    <div className="flex flex-wrap items-center gap-2 py-2.5">
                        <div className="flex flex-wrap items-center gap-1.5">
                            {Array.from({ length: FILTER_COUNT }).map((_, i) => (
                                <Skeleton key={i} className="h-9 w-24 rounded-full" />
                            ))}
                        </div>
                        <div className="ml-auto w-full max-w-xl">
                            <Skeleton className="h-9 w-full rounded-full" />
                        </div>
                    </div>

                    <div className="flex items-center justify-between gap-3 px-1">
                        <Skeleton className="h-3.5 w-40" />
                        <Skeleton className="h-8 w-40 rounded-full" />
                    </div>

                    <ul className="relative space-y-0">
                        <li className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3">
                            <div className="flex flex-col items-center pt-3.5">
                                <Skeleton className="size-2 rounded-full" />
                            </div>
                            <div className="flex items-baseline gap-2 pt-2 pb-1.5">
                                <Skeleton className="h-4 w-32" />
                                <Skeleton className="h-3 w-6" />
                            </div>
                        </li>
                        {Array.from({ length: TIMELINE_ROWS }).map((_, i) => (
                            <li key={i} className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3">
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
                </div>
            </div>
        </div>
    );
}
