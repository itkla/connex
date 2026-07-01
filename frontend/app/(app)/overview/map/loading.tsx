import { Skeleton } from '@/components/ui/skeleton';

export default function MapLoading() {
    return (
        <div className="flex min-h-0 w-full flex-1 flex-col gap-4">
            <div className="flex flex-col gap-2">
                <Skeleton className="h-9 w-56 md:h-10" />
                <Skeleton className="h-4 w-72" />
            </div>
            <div className="relative min-h-0 w-full flex-1 overflow-hidden rounded-lg">
                <Skeleton className="h-full w-full rounded-lg" />
                <div className="absolute right-4 top-4 flex flex-col gap-2 rounded-2xl border border-border bg-card p-3">
                    {Array.from({ length: 4 }).map((_, i) => (
                        <div key={i} className="flex items-center gap-2">
                            <Skeleton className="size-3 rounded-full" />
                            <Skeleton className="h-3 w-20" />
                        </div>
                    ))}
                </div>
                <div className="absolute bottom-4 right-4 flex flex-col gap-1 rounded-2xl border border-border bg-card p-2">
                    {Array.from({ length: 4 }).map((_, i) => (
                        <Skeleton key={i} className="size-7 rounded-md" />
                    ))}
                </div>
                <div className="absolute bottom-4 left-4 flex items-center gap-2 rounded-2xl border border-border bg-card px-3 py-2">
                    <Skeleton className="size-8 rounded-full" />
                    <Skeleton className="h-4 w-28" />
                </div>
            </div>
        </div>
    );
}
