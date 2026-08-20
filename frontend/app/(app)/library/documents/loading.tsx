import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

function DocumentRow() {
    return (
        <div className="flex items-center gap-6 px-4 py-3 sm:px-6">
            <div className="min-w-0 flex-1 space-y-1.5">
                <Skeleton className="h-4 w-48 max-w-full" />
                <Skeleton className="h-3 w-32" />
            </div>
            <Skeleton className="hidden h-4 w-24 md:block" />
            <Skeleton className="h-4 w-16" />
            <Skeleton className="hidden h-4 w-20 sm:block" />
            <Skeleton className="hidden h-4 w-24 lg:block" />
        </div>
    );
}

export default function DocumentsLibraryLoading() {
    return (
        <PageShell>
                <div className="space-y-2">
                    <Skeleton className="h-9 w-64 max-w-full sm:h-10" />
                    <Skeleton className="h-4 w-96 max-w-full" />
                </div>

                <Skeleton className="h-8 w-56 rounded-full" />

                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div className="w-full sm:w-64">
                        <Skeleton className="h-9 w-full rounded-full" />
                    </div>
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-8 w-24 rounded-full" />
                        <Skeleton className="h-8 w-20 rounded-full" />
                    </div>
                </div>

                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-6 border-b border-border px-4 py-3 sm:px-6">
                        <div className="min-w-0 flex-1">
                            <Skeleton className="h-3 w-16" />
                        </div>
                        <Skeleton className="hidden h-3 w-12 md:block" />
                        <Skeleton className="h-3 w-12" />
                        <Skeleton className="hidden h-3 w-12 sm:block" />
                        <Skeleton className="hidden h-3 w-12 lg:block" />
                    </div>
                    <div className="divide-y divide-border">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <DocumentRow key={i} />
                        ))}
                    </div>
                </div>
        </PageShell>
    );
}
