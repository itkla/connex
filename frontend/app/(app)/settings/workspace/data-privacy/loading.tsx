import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for Data & privacy: the page heading, then the import section — its own name
 * over the single row that opens the interaction-history import.
 */
export default function WorkspaceDataPrivacyLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <div className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-28" />
                    <Skeleton className="h-3.5 w-80 max-w-full" />
                </div>

                <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
                    <div className="flex min-w-0 items-start gap-3">
                        <Skeleton className="size-10 shrink-0 rounded-xl" />
                        <div className="min-w-0 space-y-2">
                            <Skeleton className="h-3.5 w-44" />
                            <Skeleton className="h-3 w-64 max-w-full" />
                        </div>
                    </div>
                    <Skeleton className="h-9 w-36 shrink-0 rounded-md" />
                </div>
            </div>
        </div>
    );
}
