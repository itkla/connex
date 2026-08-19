import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

function PolicyRow() {
    return (
        <div className="flex items-center gap-6 px-6 py-3">
            <div className="min-w-0 flex-1">
                <Skeleton className="h-4 w-48 max-w-full" />
            </div>
            <Skeleton className="h-4 w-20" />
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-4 w-14" />
            <Skeleton className="size-6 shrink-0 rounded-md" />
        </div>
    );
}

export default function ApprovalPoliciesLoading() {
    return (
        <PageShell>
                <div className="flex items-center justify-between">
                    <Skeleton className="h-10 w-64" />
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-9 w-32 rounded-md" />
                        <Skeleton className="h-9 w-28 rounded-md" />
                    </div>
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
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="h-3 w-12" />
                        <div className="w-6 shrink-0" />
                    </div>
                    <div className="divide-y divide-border">
                        {Array.from({ length: 5 }).map((_, i) => (
                            <PolicyRow key={i} />
                        ))}
                    </div>
                </div>
        </PageShell>
    );
}
