import { Skeleton } from "@/components/ui/skeleton";

/**
 * Route-level skeleton for the settings index, which only redirects to the members tab. Kept to a
 * bare section header so nothing structural flashes before the redirect resolves.
 */
export default function SettingsLoading() {
    return (
        <div className="space-y-1">
            <Skeleton className="h-3 w-28" />
            <Skeleton className="h-4 w-64 max-w-full" />
        </div>
    );
}
