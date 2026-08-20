import { PageShell } from "@/app/components/PageShell";
import { Skeleton } from "@/components/ui/skeleton";

export default function WorkflowRecipesLoading() {
    return (
        <PageShell>
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>
            <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {Array.from({ length: 3 }, (_, index) => (
                    <div key={index} className="space-y-3 p-5">
                        <Skeleton className="h-5 w-56 max-w-full" />
                        <Skeleton className="h-4 w-96 max-w-full" />
                    </div>
                ))}
            </div>
        </PageShell>
    );
}
