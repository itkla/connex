import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <div className="flex items-start justify-between gap-4">
                    <div className="space-y-2">
                        <Skeleton className="h-10 w-32" />
                        <Skeleton className="h-4 w-80" />
                    </div>
                    <div className="space-y-2 text-right">
                        <Skeleton className="ml-auto h-4 w-20" />
                        <Skeleton className="ml-auto h-3 w-16" />
                    </div>
                </div>

                <div className="flex items-center justify-between gap-3">
                    <div className="flex gap-2">
                        <Skeleton className="h-8 w-28 rounded-full" />
                        <Skeleton className="h-8 w-28 rounded-full" />
                        <Skeleton className="h-8 w-24 rounded-full" />
                    </div>
                    <div className="flex gap-2">
                        <Skeleton className="h-9 w-64 rounded-full" />
                        <Skeleton className="h-9 w-20 rounded-full" />
                    </div>
                </div>

                <ul className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,16rem),1fr))] gap-4">
                    {Array.from({ length: 10 }).map((_, i) => (
                        <li key={i} className="overflow-hidden rounded-2xl border border-border bg-card">
                            <div className="flex items-center gap-2 px-3 py-2.5">
                                <Skeleton className="size-4 rounded" />
                                <Skeleton className="h-4 flex-1" />
                            </div>
                            <Skeleton className="aspect-[4/3] w-full rounded-none" />
                            <div className="flex items-center justify-between px-3 py-2">
                                <Skeleton className="h-3 w-20" />
                                <Skeleton className="h-3 w-12" />
                            </div>
                        </li>
                    ))}
                </ul>
            </div>
        </div>
    );
}
