import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

function TemplateRow() {
    return (
        <div className="flex items-center gap-6 px-4 py-3 sm:px-6">
            <div className="min-w-0 flex-1 space-y-1.5">
                <Skeleton className="h-4 w-48 max-w-full" />
                <Skeleton className="h-3 w-32" />
            </div>
            <Skeleton className="hidden h-4 w-20 md:block" />
            <Skeleton className="hidden h-4 w-10 sm:block" />
            <Skeleton className="h-4 w-14" />
            <Skeleton className="size-6 shrink-0 rounded-md" />
        </div>
    );
}

export default function DocumentTemplatesLoading() {
    return (
        <PageShell>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <Skeleton className="h-9 w-64 max-w-full sm:h-10" />
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-9 w-36 rounded-md" />
                        <Skeleton className="h-9 w-28 rounded-md" />
                    </div>
                </div>

                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <div className="w-full sm:w-64">
                        <Skeleton className="h-9 w-full rounded-full" />
                    </div>
                </div>

                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-6 border-b border-border px-4 py-3 sm:px-6">
                        <div className="min-w-0 flex-1">
                            <Skeleton className="h-3 w-16" />
                        </div>
                        <Skeleton className="hidden h-3 w-12 md:block" />
                        <Skeleton className="hidden h-3 w-12 sm:block" />
                        <Skeleton className="h-3 w-12" />
                        <div className="w-6 shrink-0" />
                    </div>
                    <div className="divide-y divide-border">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <TemplateRow key={i} />
                        ))}
                    </div>
                </div>
        </PageShell>
    );
}
