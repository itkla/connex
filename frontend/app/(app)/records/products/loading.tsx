import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

export default function ProductsLoading() {
    return (
        <PageShell>
                <div className="flex items-start justify-between gap-4">
                    <div className="space-y-2">
                        <Skeleton className="h-10 w-56" />
                        <Skeleton className="h-4 w-72 max-w-full" />
                    </div>
                    <Skeleton className="h-9 w-40 rounded-full" />
                </div>

                <div className="rounded-2xl py-2.5">
                    <div className="flex flex-wrap items-center gap-2">
                        <Skeleton className="h-9 w-72 max-w-full rounded-full" />
                        <div className="ml-auto flex flex-wrap items-center gap-2">
                            <Skeleton className="h-9 w-20 rounded-full" />
                            <Skeleton className="h-9 w-24 rounded-full" />
                        </div>
                    </div>
                </div>

                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-5 border-b border-border bg-muted/60 px-4 py-2.5">
                        <Skeleton className="size-4 rounded-sm" />
                        {Array.from({ length: 6 }).map((_, index) => (
                            <Skeleton key={index} className="h-3 w-16" />
                        ))}
                    </div>
                    <div className="divide-y divide-border">
                        {Array.from({ length: 8 }).map((_, index) => (
                            <div key={index} className="flex items-center gap-5 px-4 py-3">
                                <Skeleton className="size-4 rounded-sm" />
                                <Skeleton className="h-4 w-44 max-w-full" />
                                <Skeleton className="hidden h-4 w-16 sm:block" />
                                <Skeleton className="hidden h-4 w-20 sm:block" />
                                <Skeleton className="hidden h-4 w-16 md:block" />
                                <Skeleton className="hidden h-4 w-12 md:block" />
                                <Skeleton className="ml-auto h-6 w-20 rounded-full" />
                            </div>
                        ))}
                    </div>
                </div>
        </PageShell>
    );
}
