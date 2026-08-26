import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for Connected accounts: the page heading, then the panel's own name over the
 * two provider cards a reader arrives to, closed by the line about what Connex does with what it
 * captures.
 */
export default function PersonalConnectedAccountsLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-72" />
            </div>

            <div className="space-y-4">
                <div className="space-y-1">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-32" />
                    </div>
                    <div className="px-6">
                        <Skeleton className="h-4 w-80 max-w-full" />
                    </div>
                </div>

                <div className="grid gap-3">
                    {Array.from({ length: 2 }, (_, card) => (
                        <Skeleton key={card} className="h-28 w-full rounded-2xl" />
                    ))}
                </div>

                <div className="px-6">
                    <Skeleton className="h-3 w-72 max-w-full" />
                </div>
            </div>
        </div>
    );
}
