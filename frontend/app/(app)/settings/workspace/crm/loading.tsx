import QualificationCriteriaSkeleton from "@/app/components/settings/QualificationCriteriaSkeleton";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * First-load stand-in for CRM configuration: the page heading, then the four sections it composes —
 * the three record types' custom fields, the qualification criteria, the approval-policy table, and
 * the notice standing in for workflow configuration.
 */
function SectionHeading({ withAction }: { withAction?: boolean }) {
    return (
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
            <div className="space-y-1.5">
                <Skeleton className="h-4 w-44" />
                <Skeleton className="h-3.5 w-80 max-w-full" />
            </div>
            {withAction ? <Skeleton className="h-8 w-28 shrink-0 rounded-full" /> : null}
        </div>
    );
}

function FieldRows({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, row) => (
                <li key={row} className="flex items-center gap-3 px-4 py-3.5">
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-32" />
                        <Skeleton className="h-3 w-48" />
                    </div>
                </li>
            ))}
        </ul>
    );
}

export default function CrmConfigurationLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <section className="space-y-4">
                <SectionHeading />
                {Array.from({ length: 3 }, (_, entity) => (
                    <div key={entity} className="space-y-3 pt-2">
                        <div className="flex items-center justify-between gap-4">
                            <Skeleton className="h-3.5 w-24" />
                            <Skeleton className="h-8 w-28 rounded-full" />
                        </div>
                        <FieldRows rows={2} />
                    </div>
                ))}
            </section>

            <section className="space-y-4">
                <SectionHeading withAction />
                <QualificationCriteriaSkeleton />
            </section>

            <section className="space-y-4">
                <SectionHeading withAction />
                <div className="flex items-center justify-between gap-3">
                    <Skeleton className="h-3 w-24" />
                    <Skeleton className="h-9 w-64 rounded-full" />
                </div>
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="flex items-center gap-6 border-b border-border px-6 py-3">
                        <div className="min-w-0 flex-1">
                            <Skeleton className="h-3 w-16" />
                        </div>
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="h-3 w-12" />
                        <div className="w-6 shrink-0" />
                    </div>
                    <div className="divide-y divide-border">
                        {Array.from({ length: 4 }, (_, row) => (
                            <div key={row} className="flex items-center gap-6 px-6 py-3">
                                <div className="min-w-0 flex-1">
                                    <Skeleton className="h-4 w-48 max-w-full" />
                                </div>
                                <Skeleton className="h-4 w-20" />
                                <Skeleton className="h-4 w-40" />
                                <Skeleton className="h-4 w-14" />
                                <Skeleton className="size-6 shrink-0 rounded-md" />
                            </div>
                        ))}
                    </div>
                </div>
            </section>

            <section className="space-y-4">
                <SectionHeading />
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-border bg-card/40 px-6 py-12">
                    <Skeleton className="h-4 w-40" />
                    <Skeleton className="h-3.5 w-72 max-w-full" />
                    <Skeleton className="mt-1 h-8 w-36 rounded-full" />
                </div>
            </section>
        </div>
    );
}
