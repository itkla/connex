import { Skeleton } from "@/components/ui/skeleton";

/**
 * Route-level skeleton for the notifications tab, which only redirects to the account notifications
 * page. Kept to a bare section header so nothing structural flashes before the redirect resolves.
 */
export default function NotificationsSettingsLoading() {
    return (
        <div className="space-y-1">
            <Skeleton className="h-3 w-28" />
            <Skeleton className="h-4 w-64 max-w-full" />
        </div>
    );
}
