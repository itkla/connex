import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for Profile: the page heading, then the identity form the panel opens with —
 * the photo beside the name, five fields under it — and the read-only details block below.
 */
export default function PersonalProfileLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <div className="space-y-10">
                <div className="space-y-6 rounded-2xl border border-border bg-card p-6">
                    <div className="flex items-center gap-4">
                        <Skeleton className="size-20 shrink-0 rounded-full" />
                        <div className="space-y-2">
                            <Skeleton className="h-4 w-28" />
                            <Skeleton className="h-3 w-44" />
                        </div>
                    </div>
                    {Array.from({ length: 5 }, (_, field) => (
                        <div key={field} className="space-y-2">
                            <Skeleton className="h-3.5 w-24" />
                            <Skeleton className="h-9 w-full rounded-lg" />
                        </div>
                    ))}
                </div>

                <div className="space-y-3">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-20" />
                    </div>
                    <div className="space-y-3 rounded-2xl border border-border bg-card p-6">
                        <Skeleton className="h-3.5 w-56" />
                        <Skeleton className="h-3.5 w-48" />
                    </div>
                </div>
            </div>
        </div>
    );
}
