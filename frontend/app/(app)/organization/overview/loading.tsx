import { Skeleton } from "@/components/ui/skeleton";

export default function OrganizationOverviewLoading() {
    return (
        <div className="space-y-10">
            <Skeleton className="h-56 rounded-2xl" />
            <Skeleton className="h-96 rounded-2xl" />
        </div>
    );
}
