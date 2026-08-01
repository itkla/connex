import { Skeleton } from "@/components/ui/skeleton";

export default function AccountConnectionsLoading() {
    return (
        <div className="space-y-4">
            <div className="space-y-1">
                <div className="mb-3 flex h-8 items-center px-6">
                    <Skeleton className="h-3 w-32" />
                </div>
                <div className="px-6">
                    <Skeleton className="h-4 w-80 max-w-full" />
                </div>
            </div>

            <div className="grid gap-3">
                {Array.from({ length: 2 }).map((_, i) => (
                    <Skeleton key={i} className="h-28 w-full rounded-2xl" />
                ))}
            </div>

            <div className="px-6">
                <Skeleton className="h-3 w-72 max-w-full" />
            </div>
        </div>
    );
}
