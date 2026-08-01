import { Skeleton } from "@/components/ui/skeleton";
import { PageShell } from "@/app/components/PageShell";

function SectionLabel() {
    return (
        <div className="mb-3 flex h-8 items-center px-6">
            <Skeleton className="h-3 w-24" />
        </div>
    );
}

export default function CampaignDetailLoading() {
    return (
        <PageShell tier="reading">
                <div className="flex flex-col gap-4">
                    <Skeleton className="h-5 w-28" />
                    <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
                        <div className="flex items-center gap-3">
                            <Skeleton className="h-10 w-64 max-w-full" />
                            <Skeleton className="h-6 w-20 rounded-full" />
                        </div>
                        <Skeleton className="h-8 w-32 rounded-md" />
                    </div>
                </div>

                <div className="flex flex-col gap-6">
                    <Skeleton className="h-9 w-full max-w-lg rounded-lg" />

                    <div className="flex flex-col gap-8">
                        <div>
                            <SectionLabel />
                            <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                {Array.from({ length: 6 }).map((_, i) => (
                                    <div key={i} className="flex flex-col gap-2 px-6 py-4">
                                        <Skeleton className="h-3 w-20" />
                                        <Skeleton className="h-4 w-48 max-w-full" />
                                    </div>
                                ))}
                            </div>
                        </div>
                        <div>
                            <SectionLabel />
                            <div className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-4">
                                {Array.from({ length: 4 }).map((_, i) => (
                                    <div key={i} className="flex flex-col gap-2 bg-card p-4 sm:p-5">
                                        <Skeleton className="h-3 w-20" />
                                        <Skeleton className="h-7 w-16" />
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </div>
        </PageShell>
    );
}
