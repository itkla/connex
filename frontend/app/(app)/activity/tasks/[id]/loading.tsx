import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-3xl flex-col gap-8">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-8 w-3/4" />
                <div className="space-y-3 rounded-2xl border border-border bg-card p-6">
                    <Skeleton className="h-4 w-40" />
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-4 w-48" />
                </div>
            </div>
        </div>
    );
}
