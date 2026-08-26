import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for Workspaces & invitations: the page heading, then the two blocks the panel
 * opens with — the invitations waiting, and the way out of the workspace already joined.
 */
export default function PersonalWorkspacesLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-80" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <div className="space-y-10">
                <section className="space-y-3">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-32" />
                    </div>
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <div className="flex items-center gap-3 px-4 py-3">
                            <Skeleton className="size-8 shrink-0 rounded-full" />
                            <Skeleton className="h-3.5 w-40" />
                        </div>
                    </div>
                </section>

                <section className="space-y-3">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-28" />
                    </div>
                    <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card px-4 py-4">
                        <Skeleton className="h-4 w-64" />
                        <Skeleton className="h-9 w-20 rounded-md" />
                    </div>
                </section>
            </div>
        </div>
    );
}
