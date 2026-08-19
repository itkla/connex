import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

function ProductRow() {
    return (
        <div className="p-4 xl:grid xl:grid-cols-[minmax(12rem,1fr)_6rem_7rem_6rem_5rem_10rem_2rem] xl:items-center xl:gap-5 xl:px-5 xl:py-3.5">
            <div className="min-w-0 space-y-1.5">
                <Skeleton className="h-4 w-44 max-w-full" />
                <Skeleton className="h-3 w-32 max-w-full" />
            </div>
            <div className="mt-4 flex items-end justify-between xl:contents">
                <Skeleton className="hidden h-4 w-16 xl:block" />
                <Skeleton className="h-6 w-24 xl:h-4 xl:w-20" />
                <Skeleton className="h-4 w-16" />
                <Skeleton className="hidden h-4 w-10 xl:block" />
                <Skeleton className="hidden h-8 w-24 rounded-full xl:block" />
                <Skeleton className="size-10 shrink-0 rounded-md xl:size-6" />
            </div>
            <div className="mt-4 grid grid-cols-2 gap-3 border-t border-border pt-3 xl:hidden">
                <Skeleton className="h-8 w-20" />
                <Skeleton className="h-8 w-20" />
                <Skeleton className="col-span-2 h-8 w-40" />
            </div>
        </div>
    );
}

export default function ProductsLoading() {
    return (
        <PageShell>
                <div className="flex items-start justify-between gap-4">
                    <div className="space-y-2">
                        <Skeleton className="h-10 w-56" />
                        <Skeleton className="h-4 w-72 max-w-full" />
                    </div>
                    <Skeleton className="h-9 w-40 rounded-md" />
                </div>

                <div>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <Skeleton className="h-9 w-full rounded-full sm:max-w-sm" />
                </div>

                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="hidden grid-cols-[minmax(12rem,1fr)_6rem_7rem_6rem_5rem_10rem_2rem] items-center gap-5 border-b border-border px-5 py-3 xl:grid">
                        {Array.from({ length: 6 }).map((_, index) => (
                            <Skeleton key={index} className="h-3 w-12" />
                        ))}
                        <div />
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
