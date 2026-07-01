import { Skeleton } from "@/components/ui/skeleton";

export default function PipelinesLoading() {
    return (
        <div className="min-h-screen bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <div className="flex items-center justify-between">
                    <Skeleton className="h-10 w-56" />
                    <Skeleton className="h-9 w-24 rounded-md" />
                </div>

                <div className="rounded-2xl py-2.5">
                    <div className="flex flex-wrap items-center gap-2">
                        <div className="ml-auto flex flex-wrap items-center gap-2">
                            <Skeleton className="h-9 w-16 rounded-full" />
                            <Skeleton className="h-9 w-72 max-w-full rounded-full" />
                        </div>
                    </div>
                </div>

                <div className="grid grid-cols-1 gap-3">
                    {Array.from({ length: 8 }).map((_, i) => (
                        <div
                            key={i}
                            className="flex items-center gap-4 rounded-2xl border border-border bg-card p-4"
                        >
                            <div className="min-w-0 flex-1">
                                <Skeleton className="h-5 w-48 max-w-full" />
                            </div>
                            <Skeleton className="size-9 shrink-0 rounded-full" />
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
