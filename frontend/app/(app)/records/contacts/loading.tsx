import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

export default function ContactsLoading() {
    return (
        <PageShell tier="wide">
                <div className="flex items-center justify-between">
                    <Skeleton className="h-10 w-48" />
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-9 w-28 rounded-md" />
                        <Skeleton className="h-9 w-28 rounded-md" />
                    </div>
                </div>

                <div className="flex items-center gap-1 overflow-x-auto">
                    {Array.from({ length: 3 }).map((_, i) => (
                        <Skeleton key={i} className="h-8 w-20 rounded-md" />
                    ))}
                    <Skeleton className="ml-1 h-8 w-24 rounded-md" />
                </div>

                <div className="rounded-2xl py-2.5">
                    <div className="flex flex-wrap items-center gap-2">
                        <div className="ml-auto flex flex-wrap items-center gap-2">
                            <Skeleton className="h-9 w-9 rounded-full" />
                            <Skeleton className="h-9 w-24 rounded-full" />
                            <Skeleton className="h-9 w-64 rounded-full" />
                        </div>
                    </div>
                </div>

                <div className="space-y-2 rounded-2xl border border-border bg-card p-4">
                    {Array.from({ length: 6 }).map((_, i) => (
                        <Skeleton key={i} className="h-9 w-full rounded-lg" />
                    ))}
                </div>

                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div className="flex items-center gap-3">
                        <Skeleton className="h-4 w-40" />
                        <Skeleton className="h-8 w-36 rounded-full" />
                    </div>
                    <Skeleton className="h-8 w-52 rounded-lg" />
                </div>
        </PageShell>
    );
}
