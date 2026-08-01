import { Skeleton } from "@/components/ui/skeleton";
import { PageShell } from "@/app/components/PageShell";

export default function Loading() {
    return (
        <PageShell tier="form">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-8 w-3/4" />
                <div className="space-y-3 rounded-2xl border border-border bg-card p-6">
                    <Skeleton className="h-4 w-40" />
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-4 w-48" />
                </div>
        </PageShell>
    );
}
