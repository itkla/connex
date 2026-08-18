import { Skeleton } from "@/components/ui/skeleton";

/**
 * Stands in for the qualification criteria panel: the section heading over the criterion rows the
 * panel resolves per dimension.
 */
export default function QualificationSettingsLoading() {
    return (
        <div className="space-y-4">
            <div className="space-y-1.5">
                <Skeleton className="h-4 w-48" />
                <Skeleton className="h-3.5 w-80 max-w-full" />
            </div>

            <div className="space-y-3">
                {Array.from({ length: 3 }, (_, row) => (
                    <Skeleton key={row} className="h-16 w-full rounded-2xl" />
                ))}
            </div>
        </div>
    );
}
