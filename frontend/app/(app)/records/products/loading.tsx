import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

function ProductRow() {
    return (
        <div className="flex items-center gap-6 px-6 py-3">
            <div className="min-w-0 flex-1 space-y-1.5">
                <Skeleton className="h-4 w-44 max-w-full" />
                <Skeleton className="h-3 w-24" />
            </div>
            <Skeleton className="h-4 w-20" />
            <Skeleton className="h-4 w-16" />
            <Skeleton className="h-4 w-20" />
            <Skeleton className="h-4 w-14" />
            <Skeleton className="size-6 shrink-0 rounded-md" />
        </div>
    );
}

export default function ProductsLoading() {
    return (
        <PageShell tier="wide">
                <div className="flex items-center justify-between">
                    <Skeleton className="h-10 w-56" />
                    <Skeleton className="h-9 w-40 rounded-md" />
                </div>

                <div className="flex items-center justify-between gap-3">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <div className="w-64">
                        <Skeleton className="h-9 w-full rounded-full" />
                    </div>
                </div>

                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-6 border-b border-border px-6 py-3">
                        <div className="min-w-0 flex-1">
                            <Skeleton className="h-3 w-16" />
                        </div>
                        <Skeleton className="h-3 w-12" />
                        <Skeleton className="h-3 w-12" />
                        <Skeleton className="h-3 w-16" />
                        <Skeleton className="h-3 w-12" />
                        <div className="w-6 shrink-0" />
                    </div>
                    <div className="divide-y divide-border">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <ProductRow key={i} />
                        ))}
                    </div>
                </div>
        </PageShell>
    );
}
