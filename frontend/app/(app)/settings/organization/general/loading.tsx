import OrganizationOverviewSkeleton from "@/app/components/organization/OrganizationOverviewSkeleton";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for the organization's General destination: the page heading, then the
 * overview panel's own shared skeleton — the same one its legacy route draws, so the content keeps
 * one shape in both homes.
 */
export default function OrganizationGeneralLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <OrganizationOverviewSkeleton />
        </div>
    );
}
