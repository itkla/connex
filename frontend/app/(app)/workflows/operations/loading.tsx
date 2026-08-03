import { PageShell } from "@/app/components/PageShell";
import { Skeleton } from "@/components/ui/skeleton";

export default function WorkflowOperationsLoading() {
    return (
        <PageShell tier="wide">
            <div className="space-y-2">
                <Skeleton className="h-10 w-72 max-w-full" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>
            <div className="grid gap-px overflow-hidden rounded-2xl border border-border bg-border sm:grid-cols-3 xl:grid-cols-6">
                {Array.from({ length: 6 }, (_, index) => (
                    <div key={index} className="space-y-3 bg-card p-4">
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="h-7 w-12" />
                    </div>
                ))}
            </div>
            <div className="space-y-3">
                <Skeleton className="h-6 w-40" />
                <Skeleton className="h-64 w-full rounded-2xl" />
            </div>
        </PageShell>
    );
}
