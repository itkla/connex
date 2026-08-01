import { Skeleton } from "@/components/ui/skeleton";

/**
 * Route-level skeleton for the organization index, which only redirects to the members tab. Kept to
 * a bare section header so nothing structural flashes before the redirect resolves.
 */
export default function OrganizationLoading() {
    return (
        <div>
            <div className="mb-3 flex h-8 items-center px-6">
                <Skeleton className="h-3 w-32" />
            </div>
            <div className="px-6">
                <Skeleton className="h-4 w-72 max-w-full" />
            </div>
        </div>
    );
}
