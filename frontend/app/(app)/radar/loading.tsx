import { PageShell } from '@/app/components/PageShell';
import { Skeleton } from '@/components/ui/skeleton';

function SignalSkeleton() {
    return (
        <li className="rounded-2xl border border-border bg-card p-5 sm:p-6">
            <div className="flex flex-col gap-5 xl:grid xl:grid-cols-[minmax(14rem,0.8fr)_minmax(22rem,1.5fr)_minmax(17rem,1fr)] xl:gap-8">
                <div className="flex gap-3">
                    <Skeleton className="size-10 shrink-0 rounded-xl" />
                    <div className="space-y-2">
                        <Skeleton className="h-3 w-24" />
                        <Skeleton className="h-5 w-40" />
                        <Skeleton className="h-3 w-20" />
                    </div>
                </div>
                <div className="space-y-3">
                    <Skeleton className="h-3 w-32" />
                    <Skeleton className="h-4 w-full" />
                    <Skeleton className="h-4 w-4/5" />
                </div>
                <div className="space-y-3 xl:items-end">
                    <Skeleton className="h-4 w-32 xl:ml-auto" />
                    <Skeleton className="h-11 w-full" />
                </div>
            </div>
        </li>
    );
}

export default function RadarLoading() {
    return (
        <PageShell tier="wide">
            <header className="space-y-2">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-4 w-full max-w-xl" />
            </header>
            <div className="space-y-8">
                <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-4 sm:flex-row">
                    <Skeleton className="h-11 flex-1" />
                    <Skeleton className="h-11 w-full sm:w-40" />
                    <Skeleton className="h-11 w-full sm:w-36" />
                </div>
                <ul className="space-y-4">
                    {Array.from({ length: 3 }).map((_, index) => <SignalSkeleton key={index} />)}
                </ul>
            </div>
        </PageShell>
    );
}
