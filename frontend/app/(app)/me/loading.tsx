import { Skeleton } from "@/components/ui/skeleton";

export default function MeLoading() {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-8">
                <div className="relative grid min-h-[30rem] place-items-center overflow-hidden rounded-3xl border border-border bg-card sm:min-h-[34rem]">
                    <Skeleton className="size-24 rounded-full sm:size-28" />
                    <div className="absolute left-6 top-6 space-y-2 sm:left-8 sm:top-8">
                        <Skeleton className="h-4 w-28" />
                        <Skeleton className="h-10 w-56" />
                    </div>
                </div>

                <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-2 lg:grid-cols-4">
                    {Array.from({ length: 4 }).map((_, i) => (
                        <div key={i} className="flex flex-col gap-3 bg-card p-5">
                            <div className="flex items-center justify-between">
                                <Skeleton className="size-8 rounded-lg" />
                                <Skeleton className="h-6 w-16" />
                            </div>
                            <Skeleton className="h-7 w-24" />
                            <Skeleton className="h-3 w-20" />
                        </div>
                    ))}
                </div>

                <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                    {Array.from({ length: 2 }).map((_, i) => (
                        <div key={i} className="overflow-hidden rounded-2xl border border-border bg-card">
                            <div className="flex items-center gap-2.5 border-b border-border px-5 py-4">
                                <Skeleton className="size-8 rounded-lg" />
                                <Skeleton className="h-4 w-32" />
                            </div>
                            {Array.from({ length: 3 }).map((_, j) => (
                                <div key={j} className="flex items-center gap-3 px-5 py-3">
                                    <Skeleton className="size-9 shrink-0 rounded-full" />
                                    <div className="flex-1 space-y-1.5">
                                        <Skeleton className="h-3.5 w-32" />
                                        <Skeleton className="h-3 w-20" />
                                    </div>
                                    <Skeleton className="h-5 w-16 rounded-full" />
                                </div>
                            ))}
                        </div>
                    ))}
                </div>

                <div className="rounded-2xl border border-border bg-card p-5">
                    <Skeleton className="h-4 w-28" />
                    <Skeleton className="mt-5 h-24 w-full" />
                </div>
            </div>
        </div>
    );
}
