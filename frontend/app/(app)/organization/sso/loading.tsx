import { Skeleton } from "@/components/ui/skeleton";

export default function OrgSsoLoading() {
    return (
        <div className="space-y-3">
            <div>
                <div className="mb-3 flex h-8 items-center px-6">
                    <Skeleton className="h-3 w-32" />
                </div>
                <div className="px-6">
                    <Skeleton className="h-4 w-80 max-w-full" />
                </div>
            </div>

            <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-2/3 rounded-md" />
            </div>
        </div>
    );
}
