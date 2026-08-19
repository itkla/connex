import { Skeleton } from "@/components/ui/skeleton";
import { PageShell } from "@/app/components/PageShell";

export default function Loading() {
    return (
        <PageShell>
                <Skeleton className="h-4 w-24" />
                <div className="mx-auto w-full max-w-3xl space-y-4">
                    <Skeleton className="h-10 w-2/3" />
                    <Skeleton className="h-4 w-40" />
                    <div className="space-y-2 pt-6">
                        <Skeleton className="h-4 w-full" />
                        <Skeleton className="h-4 w-11/12" />
                        <Skeleton className="h-4 w-4/5" />
                    </div>
                </div>
        </PageShell>
    );
}
