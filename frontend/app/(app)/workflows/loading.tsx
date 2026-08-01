import { Skeleton } from "@/components/ui/skeleton";

export default function WorkflowsLoading() {
    return (
        <div className="space-y-4">
            <div>
                <div className="mb-3 flex h-8 items-center justify-between">
                    <div className="px-6">
                        <Skeleton className="h-3 w-28" />
                    </div>
                    <div className="px-1">
                        <Skeleton className="h-9 w-36 rounded-md" />
                    </div>
                </div>
                <div className="px-6">
                    <Skeleton className="h-4 w-96 max-w-full" />
                </div>
            </div>

            <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {Array.from({ length: 3 }, (_, i) => (
                    <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                        <Skeleton className="h-5 w-9 shrink-0 rounded-full" />
                        <div className="flex-1 space-y-2">
                            <Skeleton className="h-3.5 w-32" />
                            <Skeleton className="h-3 w-52" />
                            <div className="flex items-center gap-2">
                                <Skeleton className="h-5 w-16 rounded-full" />
                                <Skeleton className="h-3 w-20" />
                            </div>
                        </div>
                    </li>
                ))}
            </ul>
        </div>
    );
}
