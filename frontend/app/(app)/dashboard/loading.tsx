import { Skeleton } from '@/components/ui/skeleton';
import { PageShell } from '@/app/components/PageShell';

function SectionLabel() {
    return <Skeleton className="mb-3 h-3 w-24" />;
}

export default function DashboardLoading() {
    return (
        <PageShell tier="wide">
                <div className="flex flex-col gap-5 md:flex-row md:items-center md:justify-between md:gap-8">
                    <div className="space-y-3">
                        <Skeleton className="h-6 w-32" />
                        <Skeleton className="h-12 w-52" />
                        <Skeleton className="h-3 w-44" />
                    </div>
                    <Skeleton className="h-16 w-full rounded-lg md:w-80" />
                </div>

                <div>
                    <SectionLabel />
                    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                        {Array.from({ length: 4 }).map((_, i) => (
                            <div
                                key={i}
                                className="flex flex-col gap-4 rounded-2xl border border-border bg-card px-5 py-4"
                            >
                                <Skeleton className="size-9 rounded-xl" />
                                <Skeleton className="mt-2 h-3 w-20" />
                                <Skeleton className="h-9 w-16" />
                            </div>
                        ))}
                    </div>
                </div>

                <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
                    <div className="flex flex-col">
                        <SectionLabel />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <div className="flex items-center justify-between gap-4 border-b border-border px-6 py-5">
                                <div className="space-y-2">
                                    <Skeleton className="h-3 w-24" />
                                    <Skeleton className="h-4 w-40" />
                                </div>
                                <div className="flex gap-6">
                                    <Skeleton className="h-10 w-16" />
                                    <Skeleton className="h-10 w-16" />
                                </div>
                            </div>
                            <div className="p-6">
                                <Skeleton className="h-56 w-full" />
                            </div>
                        </div>
                    </div>

                    <div className="flex flex-col">
                        <SectionLabel />
                        <div className="rounded-2xl border border-border bg-card p-6">
                            <div className="flex items-center justify-between">
                                <Skeleton className="h-3 w-20" />
                                <Skeleton className="h-3 w-14" />
                            </div>
                            <Skeleton className="mt-4 h-12 w-20" />
                            <Skeleton className="mt-3 h-4 w-36" />
                            <div className="mt-6 space-y-4 border-t border-border pt-4">
                                {Array.from({ length: 4 }).map((_, i) => (
                                    <div key={i} className="flex items-center justify-between gap-3">
                                        <Skeleton className="h-4 w-48" />
                                        <Skeleton className="h-3 w-12" />
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </div>

                <div>
                    <SectionLabel />
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 5 }).map((_, i) => (
                            <div
                                key={i}
                                className="flex items-center gap-4 border-b border-border px-6 py-4 last:border-b-0"
                            >
                                <Skeleton className="size-9 shrink-0 rounded-full" />
                                <div className="flex-1 space-y-2">
                                    <Skeleton className="h-4 w-1/3" />
                                    <Skeleton className="h-3 w-1/2" />
                                </div>
                                <Skeleton className="h-3 w-12 shrink-0" />
                            </div>
                        ))}
                    </div>
                </div>
        </PageShell>
    );
}
