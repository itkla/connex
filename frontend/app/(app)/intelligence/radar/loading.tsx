import { PageShell } from '@/app/components/PageShell';
import { Skeleton } from '@/components/ui/skeleton';

function RowSkeleton() {
    return (
        <li className="border-b border-border/60 px-3 py-3 last:border-b-0 sm:px-4">
            <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:gap-4">
                <div className="flex min-w-0 flex-1 items-start gap-3">
                    <Skeleton className="mt-0.5 size-9 shrink-0 rounded-lg" />
                    <div className="min-w-0 flex-1 space-y-2">
                        <Skeleton className="h-4 w-48" />
                        <Skeleton className="h-3 w-56" />
                        <Skeleton className="h-4 w-full max-w-lg" />
                    </div>
                </div>
                <div className="flex shrink-0 items-center gap-1">
                    <Skeleton className="size-11 rounded-full lg:size-9" />
                    <Skeleton className="size-11 rounded-full lg:size-9" />
                    <Skeleton className="size-11 rounded-full lg:size-9" />
                    <Skeleton className="h-11 w-28 rounded-full lg:h-9" />
                    <Skeleton className="h-11 w-32 rounded-full lg:h-9" />
                    <Skeleton className="size-11 rounded-full lg:size-9" />
                </div>
            </div>
        </li>
    );
}

function BandSkeleton({ rows }: { rows: number }) {
    return (
        <section className="space-y-2">
            <div className="flex items-baseline gap-3">
                <Skeleton className="h-4 w-28" />
                <span className="h-px min-w-0 flex-1 bg-border" aria-hidden />
            </div>
            <ol className="rounded-2xl border border-border bg-card">
                {Array.from({ length: rows }).map((_, index) => <RowSkeleton key={index} />)}
            </ol>
        </section>
    );
}

export default function RadarLoading() {
    return (
        <PageShell>
            <header>
                <Skeleton className="h-10 w-48" />
            </header>
            <div className="space-y-8">
                <div className="space-y-4">
                    <Skeleton className="h-4 w-64" />
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                        <div className="flex flex-wrap items-center gap-1.5">
                            <Skeleton className="h-9 w-28 rounded-full" />
                            <Skeleton className="h-9 w-44 rounded-full" />
                            <Skeleton className="h-9 w-28 rounded-full" />
                            <Skeleton className="h-9 w-32 rounded-full" />
                        </div>
                        <div className="flex gap-2">
                            <Skeleton className="h-11 flex-1 lg:h-9 lg:w-64 lg:flex-none" />
                            <Skeleton className="h-11 w-36 shrink-0 lg:h-9" />
                        </div>
                    </div>
                </div>
                <BandSkeleton rows={2} />
                <BandSkeleton rows={3} />
            </div>
        </PageShell>
    );
}
