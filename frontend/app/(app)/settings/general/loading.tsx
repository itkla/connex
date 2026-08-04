import { Skeleton } from "@/components/ui/skeleton";

export default function GeneralSettingsLoading() {
    return (
        <div className="space-y-4">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-64 rounded-2xl" />
        </div>
    );
}
