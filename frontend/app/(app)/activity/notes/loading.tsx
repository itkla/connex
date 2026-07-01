import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
    return (
        <div className="min-h-screen bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <div className="flex items-start justify-between gap-4">
                    <div className="space-y-2">
                        <Skeleton className="h-10 w-32" />
                        <Skeleton className="h-4 w-80 max-w-full" />
                    </div>
                    <Skeleton className="h-9 w-28 rounded-md" />
                </div>

                <div className="flex flex-wrap items-center gap-3">
                    <Skeleton className="h-8 w-16 rounded-full" />
                    <Skeleton className="h-8 w-36 rounded-full" />
                    <Skeleton className="ml-auto h-9 w-full max-w-sm rounded-full" />
                </div>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                    {Array.from({ length: 8 }).map((_, i) => (
                        <div
                            key={i}
                            className="flex aspect-square flex-col rounded-2xl border border-border bg-card p-4"
                        >
                            <div className="flex-1 space-y-2">
                                <Skeleton className="h-4 w-3/4" />
                                <Skeleton className="h-3 w-full" />
                                <Skeleton className="h-3 w-5/6" />
                                <Skeleton className="h-3 w-2/3" />
                            </div>
                            <div className="mt-3 space-y-2.5 border-t border-border pt-3">
                                <Skeleton className="h-5 w-24 rounded-full" />
                                <div className="flex items-center justify-between">
                                    <div className="flex items-center gap-2">
                                        <Skeleton className="size-6 rounded-full" />
                                        <Skeleton className="h-3 w-16" />
                                    </div>
                                    <Skeleton className="h-3 w-10" />
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}