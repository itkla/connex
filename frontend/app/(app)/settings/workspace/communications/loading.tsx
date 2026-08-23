import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for Communications: the page heading, then the three sections it composes —
 * a mail form, the delivery block with its two nested channels, and the notice standing in for the
 * defaults this workspace cannot set yet.
 */
function SectionHeading() {
    return (
        <div className="space-y-1.5">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-3.5 w-80 max-w-full" />
        </div>
    );
}

function ProviderCard() {
    return (
        <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
            <div className="flex items-center gap-4">
                <div className="min-w-0 flex-1 space-y-2">
                    <Skeleton className="h-3.5 w-40" />
                    <Skeleton className="h-3 w-64 max-w-full" />
                </div>
                <Skeleton className="h-5 w-8 shrink-0 rounded-full" />
            </div>
            <div className="space-y-3 pt-2">
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-full rounded-md" />
                <Skeleton className="h-9 w-full rounded-md" />
            </div>
        </div>
    );
}

export default function WorkspaceCommunicationsLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <section className="space-y-4">
                <SectionHeading />
                <ProviderCard />
            </section>

            <section className="space-y-4">
                <SectionHeading />
                <ProviderCard />
                <div className="space-y-4 pt-6">
                    <SectionHeading />
                    <ProviderCard />
                </div>
                <div className="space-y-4 pt-6">
                    <SectionHeading />
                    <ProviderCard />
                </div>
            </section>

            <section className="space-y-4">
                <SectionHeading />
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-border bg-card/40 px-6 py-12">
                    <Skeleton className="h-4 w-40" />
                    <Skeleton className="h-3.5 w-72 max-w-full" />
                    <Skeleton className="mt-1 h-8 w-44 rounded-full" />
                </div>
            </section>
        </div>
    );
}
