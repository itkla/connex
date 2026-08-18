import { Skeleton } from "@/components/ui/skeleton";

/**
 * Stands in for the workspace identity panel: a section heading over the two-column identity form
 * — name and slug side by side, timezone across the full width — closed by its save footer.
 */
export default function GeneralSettingsLoading() {
    return (
        <div className="space-y-4">
            <div className="space-y-1.5">
                <Skeleton className="h-4 w-40" />
                <Skeleton className="h-3.5 w-80 max-w-full" />
            </div>

            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="grid gap-6 p-6 md:grid-cols-2">
                    {Array.from({ length: 2 }, (_, field) => (
                        <div key={field} className="grid gap-2">
                            <Skeleton className="h-3.5 w-28" />
                            <Skeleton className="h-9 w-full rounded-md" />
                            <Skeleton className="h-3.5 w-48" />
                        </div>
                    ))}

                    <div className="grid gap-2 md:col-span-2">
                        <Skeleton className="h-3.5 w-24" />
                        <Skeleton className="h-9 w-full rounded-md" />
                        <Skeleton className="h-3.5 w-64 max-w-full" />
                    </div>
                </div>

                <div className="flex items-center justify-end border-t border-border bg-muted/30 px-6 py-4">
                    <Skeleton className="h-9 w-28 rounded-full" />
                </div>
            </div>
        </div>
    );
}
