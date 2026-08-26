import { Skeleton } from "@/components/ui/skeleton";

const PROVIDER_FIELDS = 4;

/** First-load stand-in for AI & data governance: the page heading, then the provider form. */
export default function OrganizationAiGovernanceLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <section className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3.5 w-80 max-w-full" />
                </div>
                <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                    {Array.from({ length: PROVIDER_FIELDS }, (_, field) => (
                        <Skeleton
                            key={field}
                            className={
                                field === PROVIDER_FIELDS - 1
                                    ? "h-9 w-2/3 rounded-md"
                                    : "h-9 w-full rounded-md"
                            }
                        />
                    ))}
                </div>
            </section>
        </div>
    );
}
