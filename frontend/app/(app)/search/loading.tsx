import { Skeleton } from "@/components/ui/skeleton";

export default function SearchLoading() {
    return (
        <div className="mx-auto w-full max-w-7xl">
            <Skeleton className="mb-6 h-7 w-72" />

            <div className="space-y-8">
                {Array.from({ length: 3 }).map((_, groupIndex) => (
                    <section key={groupIndex}>
                        <Skeleton className="mb-2 h-3 w-24" />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            {Array.from({ length: 4 }).map((_, rowIndex) => (
                                <div
                                    key={rowIndex}
                                    className="flex items-center gap-3 border-b border-border px-4 py-3 last:border-0"
                                >
                                    <Skeleton className="size-8 shrink-0 rounded-full" />
                                    <div className="min-w-0 flex-1 space-y-2">
                                        <Skeleton className="h-4 w-48" />
                                        <Skeleton className="h-3 w-32" />
                                    </div>
                                </div>
                            ))}
                        </div>
                    </section>
                ))}
            </div>
        </div>
    );
}
