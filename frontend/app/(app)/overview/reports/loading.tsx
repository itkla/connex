import { Skeleton } from '@/components/ui/skeleton';

export default function ReportsLoading() {
    return (
        <div className="min-h-full bg-background px-2 pb-12 pt-8">
            <div className="mx-auto w-full max-w-[100rem]">
                <div className="flex items-end justify-between gap-4">
                    <div>
                        <Skeleton className="h-3 w-24" />
                        <Skeleton className="mt-3 h-10 w-64" />
                        <Skeleton className="mt-3 h-4 w-96 max-w-full" />
                    </div>
                    <Skeleton className="h-9 w-32" />
                </div>
                <div className="mt-10 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
                    {Array.from({ length: 4 }).map((_, index) => (
                        <Skeleton key={index} className="h-56 rounded-2xl" />
                    ))}
                </div>
                <Skeleton className="mt-10 h-7 w-48" />
                <div className="mt-4 overflow-hidden rounded-2xl border border-border">
                    {Array.from({ length: 3 }).map((_, index) => (
                        <div key={index} className="flex items-center gap-4 border-b border-border p-5 last:border-0">
                            <Skeleton className="size-10 rounded-xl" />
                            <div className="flex-1">
                                <Skeleton className="h-4 w-48" />
                                <Skeleton className="mt-2 h-3 w-72 max-w-full" />
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
