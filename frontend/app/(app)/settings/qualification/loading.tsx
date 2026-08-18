import QualificationCriteriaSkeleton from "@/app/components/settings/QualificationCriteriaSkeleton";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * Stands in for the qualification criteria panel: the section heading over the per-dimension
 * criterion cards the panel resolves.
 */
export default function QualificationSettingsLoading() {
    return (
        <div className="space-y-4">
            <div className="space-y-1.5">
                <Skeleton className="h-4 w-48" />
                <Skeleton className="h-3.5 w-80 max-w-full" />
            </div>

            <QualificationCriteriaSkeleton />
        </div>
    );
}
