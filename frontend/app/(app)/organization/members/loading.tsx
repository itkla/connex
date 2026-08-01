import { Skeleton } from "@/components/ui/skeleton";

/**
 * Neutral placeholder for the organization members panel. It deliberately mirrors only the
 * always-present list, not the invite controls: those are gated behind an admin check the panel
 * resolves after fetching, so a skeleton that drew them would promise actions many users never get.
 */
export default function OrgMembersLoading() {
    return (
        <div className="space-y-3">
            <div>
                <div className="mb-3 flex h-8 items-center px-6">
                    <Skeleton className="h-3 w-32" />
                </div>
                <div className="px-6">
                    <Skeleton className="h-4 w-72 max-w-full" />
                </div>
            </div>

            <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {Array.from({ length: 4 }).map((_, i) => (
                    <li key={i} className="flex items-center gap-3 px-4 py-3">
                        <Skeleton className="size-9 shrink-0 rounded-full" />
                        <Skeleton className="h-4 w-40" />
                    </li>
                ))}
            </ul>
        </div>
    );
}
