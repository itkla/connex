import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for Security: the page heading, then the passkey section — its own name over
 * the two rows a reader most often has enrolled.
 */
export default function PersonalSecurityLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-40" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <div className="space-y-3">
                <div className="mb-3 flex h-8 items-center px-6">
                    <Skeleton className="h-3 w-24" />
                </div>
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: 2 }, (_, row) => (
                        <li key={row} className="flex items-center gap-3 px-4 py-3.5">
                            <Skeleton className="size-9 shrink-0 rounded-lg" />
                            <div className="min-w-0 flex-1 space-y-2">
                                <Skeleton className="h-3.5 w-32" />
                                <Skeleton className="h-3 w-48 max-w-full" />
                            </div>
                        </li>
                    ))}
                </ul>
            </div>
        </div>
    );
}
