import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

export default function CalendarLoading() {
    return (
        <PageShell tier="wide">
            <div className="flex flex-col gap-3">
                <header className="flex flex-wrap items-start justify-between gap-4">
                    <Skeleton className="h-10 w-40" />
                    <Skeleton className="h-8 w-24 rounded-full" />
                </header>
                <div className="flex flex-wrap items-center justify-between gap-3">
                    <Skeleton className="h-4 w-32" />
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-8 w-16 rounded-full" />
                        <Skeleton className="size-8 rounded-full" />
                        <Skeleton className="size-8 rounded-full" />
                    </div>
                </div>
            </div>

            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="grid grid-cols-7 border-b border-border bg-card">
                    {Array.from({ length: 7 }).map((_, i) => (
                        <div key={i} className="flex items-center justify-center px-2 py-2">
                            <Skeleton className="h-2.5 w-8" />
                        </div>
                    ))}
                </div>
                <div className="grid grid-cols-7 gap-px bg-border">
                    {Array.from({ length: 42 }).map((_, i) => (
                        <div key={i} className="flex h-[140px] flex-col gap-1 bg-card p-2">
                            <div className="flex items-center justify-end">
                                <Skeleton className="size-6 rounded-full" />
                            </div>
                            {i % 4 === 0 ? (
                                <div className="flex min-h-0 flex-1 flex-col gap-1">
                                    <Skeleton className="h-4 w-full rounded-md" />
                                    {i % 8 === 0 ? <Skeleton className="h-4 w-2/3 rounded-md" /> : null}
                                </div>
                            ) : null}
                        </div>
                    ))}
                </div>
            </div>
        </PageShell>
    );
}
