import { PageShell } from "@/app/components/PageShell";
import { Skeleton } from "@/components/ui/skeleton";

export default function WorkflowRunLoading() {
    return (
        <PageShell tier="wide">
            <div className="space-y-3">
                <Skeleton className="h-8 w-24" />
                <Skeleton className="h-9 w-72 max-w-full" />
                <Skeleton className="h-4 w-56" />
            </div>
            <div className="grid gap-6 xl:grid-cols-[minmax(0,1.5fr)_minmax(18rem,1fr)]">
                <Skeleton className="h-96 rounded-2xl" />
                <div className="space-y-4">
                    <Skeleton className="h-48 rounded-2xl" />
                    <Skeleton className="h-40 rounded-2xl" />
                </div>
            </div>
        </PageShell>
    );
}
